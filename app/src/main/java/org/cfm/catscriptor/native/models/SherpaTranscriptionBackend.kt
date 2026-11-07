package org.cfm.catscriptor.native.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.pipeline.AsrSegment
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TranscriptionBackend

/**
 * GigaAM v3 CTC: один ленивый recognizer, абсолютный интервал целиком от вызывающего кода.
 * Порции до 25 секунд не перекрываются: слово на стыке может оборваться. Конкатенация текста
 * не создаёт пословных меток и не требует экспериментальных CTC posterior.
 * После ошибки или отмены backend закрывается; для повтора нужен новый экземпляр.
 */
class SherpaTranscriptionBackend internal constructor(
    private val recognize: suspend (FloatArray) -> String,
    private val resource: ModelResource,
) : TranscriptionBackend {
    constructor(context: Context, numThreads: Int = 2) : this(SherpaModelResource(context, numThreads))

    private constructor(model: SherpaModelResource) : this({ model.diagnose(it).text }, model)

    private val operation = Mutex()
    private val lifecycle = Any()
    private var closed = false

    override suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment =
        try {
            withContext(Dispatchers.IO) {
                operation.withLock {
                    checkOpen()
                    val coroutine = currentCoroutineContext()
                    coroutine.ensureActive()
                    val count = audio.sampleCount
                    require(count >= 0) { "Отрицательная длина PCM" }
                    val time = segment.time
                    require(time.endMs <= Long.MAX_VALUE / SAMPLES_PER_MS) { "Переполнение смещения PCM" }
                    val start = time.startMs * SAMPLES_PER_MS
                    val requestedEnd = time.endMs * SAMPLES_PER_MS
                    // Последняя неполная миллисекунда допустима, но не произвольный выход за запись.
                    val durationMs = count / SAMPLES_PER_MS + if (count % SAMPLES_PER_MS == 0L) 0 else 1
                    require(time.endMs <= durationMs && start < count) { "Интервал вне PCM" }
                    val end = minOf(requestedEnd, count)
                    val text = StringBuilder()
                    var offset = start
                    while (offset < end) {
                        coroutine.ensureActive()
                        checkOpen()
                        val samples = FloatArray(minOf(MAX_CHUNK_SAMPLES.toLong(), end - offset).toInt())
                        var filled = 0
                        while (filled < samples.size) {
                            coroutine.ensureActive()
                            val requested = minOf(8192, samples.size - filled)
                            val block = audio.read(offset + filled, requested)
                            coroutine.ensureActive()
                            check(block.isNotEmpty() && block.size <= requested) { "Нарушен контракт чтения PCM" }
                            require(block.all { it.isFinite() && it in -1f..1f }) { "Некорректный PCM" }
                            block.copyInto(samples, filled)
                            filled += block.size
                        }
                        checkOpen()
                        coroutine.ensureActive()
                        val part = recognize(samples).trim()
                        coroutine.ensureActive()
                        checkOpen()
                        if (part.isNotEmpty()) {
                            if (text.isNotEmpty()) text.append(' ')
                            text.append(part)
                        }
                        offset += samples.size
                    }
                    AsrSegment(time = time, text = text.toString())
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }

    private fun checkOpen() = synchronized(lifecycle) {
        check(!closed) { "ASR уже закрыт" }
    }

    /** Может ожидать текущий JNI decode; не вызывать из UI-потока. PCM не принадлежит backend. */
    override fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            resource.close()
        }
    }

    companion object {
        private const val SAMPLES_PER_MS = 16L
        internal const val MAX_CHUNK_SAMPLES = 25 * 16000
    }
}
