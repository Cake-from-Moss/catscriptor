package org.cfm.catscriptor.domain.diarization

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange

/** Интервал одного локального спикера в окне; время в секундах от начала окна. */
class LocalSegment(val speaker: Int, val startSec: Float, val endSec: Float)

/** Нативная диаризация одного окна (≤ нескольких минут PCM). Реализация владеет моделями. */
interface WindowDiarizer {
    suspend fun diarize(samples: FloatArray): List<LocalSegment>
}

/** Эмбеддинг голоса по аудио; null, если аудио слишком коротко для модели. */
interface SpeakerEmbedder {
    suspend fun embed(samples: FloatArray): FloatArray?
}

/**
 * @property embedMaxMs сколько «чистой» речи спикера окна берётся для вычисления эмбеддинга.
 * @property embedMinMs меньше этого эмбеддинг не считается; такой спикер без пары отбрасывается.
 * @property newSpeakerMinMs речь короче не может породить нового глобального спикера.
 * @property joinGapMs разрыв одного спикера на стыке окон, который закрывается склейкой интервалов.
 */
data class ChunkedDiarizerSettings(
    val embedMaxMs: Long = 15_000,
    val embedMinMs: Long = 400,
    val newSpeakerMinMs: Long = 1_000,
    val joinGapMs: Long = 200,
    val minSimilarity: Float = 0.5f,
    val readBlockSamples: Int = 8192,
)

/**
 * Диаризация записи произвольной длины ограниченными по памяти окнами.
 *
 * Одно окно: числа спикеров определяет кластеризация sherpa, эмбеддинги вне нативной модели не нужны.
 * Несколько окон: у каждого окна своя кластеризация, поэтому метки `0,1,…` окон между собой не связаны.
 * Для каждого локального спикера считается эмбеддинг по его речи без пересечений с другими спикерами,
 * после чего [GlobalSpeakerRegistry] сопоставляет голоса с глобальными. Если для сопоставления нет
 * достаточно аудио (меньше [ChunkedDiarizerSettings.embedMinMs]), интервалы спикера отбрасываются,
 * а не приписываются наугад. Итоговые `speaker_N` нумеруются по порядку появления в записи.
 */
class ChunkedDiarizer(
    private val windowDiarizer: WindowDiarizer,
    private val embedderProvider: () -> SpeakerEmbedder,
    private val planner: DiarizationWindowPlanner = DiarizationWindowPlanner(),
    private val settings: ChunkedDiarizerSettings = ChunkedDiarizerSettings(),
) {
    suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>): List<SpeakerSegment> {
        val durationMs = audio.sampleCount * 1000 / PcmAudioSource.SAMPLE_RATE_HZ
        val windows = planner.plan(speech, durationMs)
        if (windows.isEmpty()) return emptyList()

        val registry = GlobalSpeakerRegistry(settings.minSimilarity)
        var embedder: SpeakerEmbedder? = null
        val raw = ArrayList<Pair<Int, TimeRange>>()
        for (window in windows) {
            coroutineContext.ensureActive()
            val samples = readWindow(audio, window)
            val local = windowDiarizer.diarize(samples).mapNotNull { toAbsolute(it, window, durationMs) }
            coroutineContext.ensureActive()
            if (local.isEmpty()) continue
            if (windows.size == 1) {
                local.forEach { raw += it.speaker to it.time }
                continue
            }
            val speakers = local.map { it.speaker }.distinct().sorted()
            val embeddings = HashMap<Int, LocalSpeakerEmbedding>()
            for (speaker in speakers) {
                val clean = cleanRanges(speaker, local)
                val audioForEmbedding = collect(window, samples, clean) ?: continue
                val vector = (embedder ?: embedderProvider().also { embedder = it }).embed(audioForEmbedding.first)
                    ?: continue
                embeddings[speaker] = LocalSpeakerEmbedding(vector, audioForEmbedding.second)
            }
            coroutineContext.ensureActive()
            val order = speakers.filter { it in embeddings }
            val global = registry.assign(order.map { embeddings.getValue(it) }, settings.newSpeakerMinMs)
            val mapping = order.zip(global).toMap()
            local.forEach { segment -> mapping[segment.speaker]?.let { raw += it to segment.time } }
        }
        return finalize(raw)
    }

    private class Local(val speaker: Int, val time: TimeRange)

    private fun toAbsolute(segment: LocalSegment, window: TimeRange, durationMs: Long): Local? {
        if (!segment.startSec.isFinite() || !segment.endSec.isFinite()) return null
        val start = window.startMs + Math.round(segment.startSec * 1000.0)
        val end = window.startMs + Math.round(segment.endSec * 1000.0)
        val clippedStart = maxOf(window.startMs, start)
        val clippedEnd = minOf(window.endMs, durationMs, end)
        return if (clippedEnd > clippedStart) Local(segment.speaker, TimeRange(clippedStart, clippedEnd)) else null
    }

    /** Части интервалов спикера, где не звучит никто другой: эмбеддинг по ним не загрязнён чужим голосом. */
    private fun cleanRanges(speaker: Int, all: List<Local>): List<TimeRange> {
        val others = all.filter { it.speaker != speaker }.map { it.time }
        val result = ArrayList<TimeRange>()
        for (own in all.filter { it.speaker == speaker }.map { it.time }) {
            var pieces = listOf(own)
            for (other in others) {
                pieces = pieces.flatMap { subtract(it, other) }
                if (pieces.isEmpty()) break
            }
            result += pieces
        }
        return result.sortedByDescending { it.endMs - it.startMs }
    }

    private fun subtract(range: TimeRange, other: TimeRange): List<TimeRange> {
        if (other.endMs <= range.startMs || other.startMs >= range.endMs) return listOf(range)
        val result = ArrayList<TimeRange>(2)
        if (other.startMs > range.startMs) result += TimeRange(range.startMs, other.startMs)
        if (other.endMs < range.endMs) result += TimeRange(other.endMs, range.endMs)
        return result
    }

    /** Склеивает самые длинные чистые участки до [ChunkedDiarizerSettings.embedMaxMs]; null — их мало. */
    private fun collect(window: TimeRange, samples: FloatArray, ranges: List<TimeRange>): Pair<FloatArray, Long>? {
        var budgetMs = settings.embedMaxMs
        val parts = ArrayList<FloatArray>()
        var totalMs = 0L
        for (range in ranges) {
            if (budgetMs <= 0) break
            val take = minOf(range.endMs - range.startMs, budgetMs)
            val from = ((range.startMs - window.startMs) * PER_MS).toInt()
            val to = minOf(samples.size.toLong(), from + take * PER_MS).toInt()
            if (to <= from) continue
            parts += samples.copyOfRange(from, to)
            totalMs += take
            budgetMs -= take
        }
        if (totalMs < settings.embedMinMs) return null
        val joined = FloatArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { it.copyInto(joined, offset); offset += it.size }
        return joined to totalMs
    }

    private suspend fun readWindow(audio: PcmAudioSource, window: TimeRange): FloatArray {
        val start = window.startMs * PER_MS
        val end = minOf(window.endMs * PER_MS, audio.sampleCount)
        require(start < end) { "Окно вне PCM" }
        val samples = FloatArray(Math.toIntExact(end - start))
        var filled = 0
        while (filled < samples.size) {
            coroutineContext.ensureActive()
            val requested = minOf(settings.readBlockSamples, samples.size - filled)
            val block = audio.read(start + filled, requested)
            check(block.isNotEmpty() && block.size <= requested) { "Нарушен контракт чтения PCM" }
            require(block.all { it.isFinite() && it in -1f..1f }) { "Некорректный PCM" }
            block.copyInto(samples, filled)
            filled += block.size
        }
        return samples
    }

    /** Склеивает разрывы одного спикера на стыках окон, нумерует спикеров по первому появлению. */
    private fun finalize(raw: List<Pair<Int, TimeRange>>): List<SpeakerSegment> {
        val sorted = raw.sortedWith(compareBy({ it.second.startMs }, { it.second.endMs }, { it.first }))
        val order = LinkedHashMap<Int, String>()
        sorted.forEach { (speaker, _) -> order.getOrPut(speaker) { "speaker_${order.size + 1}" } }
        val merged = ArrayList<SpeakerSegment>()
        val lastIndex = HashMap<String, Int>()
        for ((speaker, time) in sorted) {
            val id = order.getValue(speaker)
            val index = lastIndex[id]
            val previous = index?.let { merged[it] }
            // Склеиваем только если между интервалами нет интервалов других спикеров.
            if (previous != null && index == merged.lastIndex &&
                time.startMs - previous.time.endMs <= settings.joinGapMs
            ) {
                merged[index] = SpeakerSegment(id, TimeRange(previous.time.startMs, maxOf(previous.time.endMs, time.endMs)))
            } else {
                merged += SpeakerSegment(id, time)
                lastIndex[id] = merged.lastIndex
            }
        }
        return merged.sortedWith(compareBy({ it.time.startMs }, { it.time.endMs }, { it.speakerId.substringAfter('_').toInt() }))
    }

    private companion object {
        const val PER_MS = 16L
    }
}
