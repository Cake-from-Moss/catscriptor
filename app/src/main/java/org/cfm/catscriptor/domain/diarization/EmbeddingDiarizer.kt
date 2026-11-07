package org.cfm.catscriptor.domain.diarization

import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange

/** Эмбеддинг голоса по аудио; null, если аудио слишком коротко для модели. */
interface SpeakerEmbedder {
    suspend fun embed(samples: FloatArray): FloatArray?
}

/**
 * Окна эмбеддингов — как в рецепте 3D-Speaker для CAM++ (1,5 с с шагом 0,75 с).
 *
 * @property minWindowMs сегмент VAD короче окна, но не короче этого, даёт одно окно на всю длину.
 *
 * Хвост сегмента после последнего полного окна (короче [hopMs]) отдельного окна не получает и
 * принадлежит спикеру последнего окна: окно, выровненное по концу реплики, захватывает затухание
 * и паддинг VAD, и на записи с 4 спикерами такие окна собирались в пятого «спикера».
 */
data class EmbeddingDiarizerSettings(
    val windowMs: Long = 1_500,
    val hopMs: Long = 750,
    val minWindowMs: Long = 500,
    val readBlockSamples: Int = 8192,
    val clustering: SpeakerClusteringSettings = SpeakerClusteringSettings(),
) {
    init {
        require(hopMs in 1..windowMs && minWindowMs in 1..windowMs && readBlockSamples > 0)
    }
}

/**
 * Диаризация по эмбеддингам коротких окон внутри речи VAD.
 *
 * 1. Каждый сегмент VAD режется на перекрывающиеся окна; для каждого окна CAM++ даёт эмбеддинг.
 * 2. [SpeakerClustering] сам определяет число спикеров и отбрасывает мелкие «ложные» кластеры.
 * 3. Внутри сегмента VAD смена спикера ставится посередине перекрытия соседних окон с разными
 *    метками; одиночное окно между двумя окнами одного спикера считается шумом и сглаживается.
 *    Сегмент без окон (короче [EmbeddingDiarizerSettings.minWindowMs]) получает спикера ближайшего
 *    по времени окна: так его речь всё равно попадёт в ASR, а не потеряется.
 *
 * Перекрытия речи не выделяются: каждый момент принадлежит одному спикеру. Итоговые `speaker_N`
 * нумеруются по первому появлению. PCM читается окнами, вся запись в память не загружается.
 */
class EmbeddingDiarizer(
    private val embedderProvider: () -> SpeakerEmbedder,
    private val settings: EmbeddingDiarizerSettings = EmbeddingDiarizerSettings(),
) {
    private val clustering = SpeakerClustering(settings.clustering)

    private class Window(val segment: Int, val time: TimeRange, val representsMs: Long)

    suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>): List<SpeakerSegment> {
        val durationMs = audio.sampleCount * 1000 / PcmAudioSource.SAMPLE_RATE_HZ
        val segments = speech.map { it.time }
            .mapNotNull { range -> minOf(range.endMs, durationMs).takeIf { it > range.startMs }?.let { TimeRange(range.startMs, it) } }
            .sortedBy { it.startMs }
        if (segments.isEmpty()) return emptyList()

        val planned = segments.flatMapIndexed { index, range -> windows(index, range) }
        val embedded = ArrayList<Window>(planned.size)
        val vectors = ArrayList<FloatArray>(planned.size)
        if (planned.isNotEmpty()) {
            val embedder = embedderProvider()
            for (window in planned) {
                currentCoroutineContext().ensureActive()
                val vector = embedder.embed(read(audio, window.time)) ?: continue
                embedded += window
                vectors += vector
            }
        }
        currentCoroutineContext().ensureActive()
        val labels = clustering.cluster(vectors, embedded.map { it.representsMs })

        val result = ArrayList<Pair<Int, TimeRange>>()
        for ((index, range) in segments.withIndex()) {
            val own = embedded.indices.filter { embedded[it].segment == index }
            if (own.isEmpty()) {
                result += nearestLabel(range, embedded, labels) to range
                continue
            }
            val smoothed = smooth(own.map { labels[it] })
            var start = range.startMs
            for (i in 0 until own.size - 1) {
                if (smoothed[i] == smoothed[i + 1]) continue
                val left = embedded[own[i]].time
                val right = embedded[own[i + 1]].time
                val boundary = ((right.startMs + left.endMs) / 2).coerceIn(start, range.endMs)
                if (boundary > start) result += smoothed[i] to TimeRange(start, boundary)
                start = boundary
            }
            if (range.endMs > start) result += smoothed.last() to TimeRange(start, range.endMs)
        }
        return number(result)
    }

    private fun windows(segment: Int, range: TimeRange): List<Window> {
        val length = range.endMs - range.startMs
        if (length < settings.windowMs) {
            return if (length >= settings.minWindowMs) listOf(Window(segment, range, length)) else emptyList()
        }
        val result = ArrayList<Window>()
        var start = range.startMs
        while (start + settings.windowMs <= range.endMs) {
            result += Window(segment, TimeRange(start, start + settings.windowMs), settings.hopMs)
            start += settings.hopMs
        }
        return result
    }

    /** Метка A B A внутри одного сегмента — почти наверняка ошибка окна, а не реплика длиной 0,75 с. */
    private fun smooth(labels: List<Int>): List<Int> {
        if (labels.size < 3) return labels
        val result = labels.toMutableList()
        for (i in 1 until labels.lastIndex) {
            if (labels[i - 1] == labels[i + 1] && labels[i] != labels[i - 1]) result[i] = labels[i - 1]
        }
        return result
    }

    private fun nearestLabel(range: TimeRange, windows: List<Window>, labels: IntArray): Int {
        if (windows.isEmpty()) return 0
        val center = (range.startMs + range.endMs) / 2
        val nearest = windows.indices.minBy { abs((windows[it].time.startMs + windows[it].time.endMs) / 2 - center) }
        return labels[nearest]
    }

    private fun number(raw: List<Pair<Int, TimeRange>>): List<SpeakerSegment> {
        val order = LinkedHashMap<Int, String>()
        return raw.sortedBy { it.second.startMs }.map { (label, time) ->
            SpeakerSegment(order.getOrPut(label) { "speaker_${order.size + 1}" }, time)
        }
    }

    private suspend fun read(audio: PcmAudioSource, time: TimeRange): FloatArray {
        val start = time.startMs * PER_MS
        val end = minOf(time.endMs * PER_MS, audio.sampleCount)
        require(start < end) { "Окно вне PCM" }
        val samples = FloatArray(Math.toIntExact(end - start))
        var filled = 0
        while (filled < samples.size) {
            currentCoroutineContext().ensureActive()
            val requested = minOf(settings.readBlockSamples, samples.size - filled)
            val block = audio.read(start + filled, requested)
            check(block.isNotEmpty() && block.size <= requested) { "Нарушен контракт чтения PCM" }
            block.copyInto(samples, filled)
            filled += block.size
        }
        return samples
    }

    private companion object {
        const val PER_MS = PcmAudioSource.SAMPLE_RATE_HZ / 1000L
    }
}
