package org.cfm.catscriptor.native.models

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.diarization.ChunkedDiarizer
import org.cfm.catscriptor.domain.diarization.ChunkedDiarizerSettings
import org.cfm.catscriptor.domain.diarization.LocalSegment
import org.cfm.catscriptor.domain.diarization.SpeakerEmbedder
import org.cfm.catscriptor.domain.diarization.WindowDiarizer
import org.cfm.catscriptor.domain.pipeline.DiarizationBackend
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource
import org.cfm.catscriptor.domain.pipeline.SpeakerSegment
import org.cfm.catscriptor.domain.pipeline.SpeechSegment

/**
 * Параметры нативной диаризации.
 *
 * @property clusteringThreshold порог косинусного расстояния агломеративной кластеризации sherpa.
 *   0,75 выбран перебором на четырёх общедоступных примерах sherpa (английский и китайский) с CAM++:
 *   значение по умолчанию 0,5 давало лишних спикеров (3/2, 4/2, 6/4). Выбор сделан на тех же файлах,
 *   это не независимая оценка и не русская речь: подлежит проверке на записях A/B/C.
 * @property numClusters -1 — число спикеров определяется автоматически (режим по умолчанию);
 *   положительное значение фиксирует его только для диагностики.
 */
data class SherpaDiarizationSettings(
    val clusteringThreshold: Float = 0.75f,
    val numClusters: Int = -1,
    val minDurationOnSec: Float = 0.2f,
    val minDurationOffSec: Float = 0.5f,
    val numThreads: Int = 2,
) {
    init {
        require(clusteringThreshold > 0f && clusteringThreshold < 2f)
        require(numClusters == -1 || numClusters >= 1)
        require(minDurationOnSec >= 0f && minDurationOffSec >= 0f && numThreads in 1..8)
    }
}

/**
 * Владелец нативных моделей диаризации: pyannote segmentation 3.0 (int8) и эмбеддинги CAM++.
 * Загружаются лениво; эмбеддер создаётся отдельно и только для записей длиннее одного окна.
 * [close] ждёт текущий JNI-вызов, поэтому его нельзя вызывать из UI-потока.
 */
internal class SherpaDiarizationResource(
    context: Context,
    private val settings: SherpaDiarizationSettings,
) : ModelResource {
    private val assets = context.applicationContext.assets
    private val store = BundledModelStore(File(context.noBackupFilesDir, "models")) { assets.open(it) }
    private val lock = Any()
    private var directory: File? = null
    private var engine: OfflineSpeakerDiarization? = null
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var closed = false

    private fun modelDirectory(cancelCheck: () -> Unit): File = directory ?: run {
        val spec = assets.open("diarization-manifest.properties").use { BundledModelSpec.readDiarization(it) }
        store.prepare(spec, cancelCheck).also { directory = it }
    }

    fun diarizeWindow(samples: FloatArray, cancelCheck: () -> Unit): List<LocalSegment> = synchronized(lock) {
        check(!closed) { "Диаризация уже закрыта" }
        cancelCheck()
        val dir = modelDirectory(cancelCheck)
        val created = engine ?: OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = File(dir, "segmentation.int8.onnx").path,
                    ),
                    numThreads = settings.numThreads,
                    provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = File(dir, "embedding.onnx").path,
                    numThreads = settings.numThreads,
                    provider = "cpu",
                ),
                clustering = FastClusteringConfig(
                    numClusters = settings.numClusters,
                    threshold = settings.clusteringThreshold,
                ),
                minDurationOn = settings.minDurationOnSec,
                minDurationOff = settings.minDurationOffSec,
            ),
        ).also { engine = it }
        cancelCheck()
        // Нативный process не прерывается: отмена обрабатывается после возврата.
        val result = created.process(samples)
        cancelCheck()
        result.map { LocalSegment(it.speaker, it.start, it.end) }
    }

    fun embed(samples: FloatArray, cancelCheck: () -> Unit): FloatArray? = synchronized(lock) {
        check(!closed) { "Диаризация уже закрыта" }
        cancelCheck()
        val dir = modelDirectory(cancelCheck)
        val created = extractor ?: SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(
                model = File(dir, "embedding.onnx").path,
                numThreads = settings.numThreads,
                provider = "cpu",
            ),
        ).also { extractor = it }
        val stream = created.createStream()
        try {
            stream.acceptWaveform(samples, PcmAudioSource.SAMPLE_RATE_HZ)
            stream.inputFinished()
            if (!created.isReady(stream)) return null
            created.compute(stream).takeIf { vector -> vector.none { it.isNaN() } }
        } finally {
            stream.release()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            val diarization = engine
            val embedding = extractor
            engine = null
            extractor = null
            diarization?.release()
            embedding?.release()
        }
    }
}

/**
 * Реальная офлайн-диаризация sherpa-onnx 2.0.1. Запись обрабатывается окнами ограниченной длины
 * ([ChunkedDiarizer]); единый вызов на всю запись не используется. После ошибки или отмены
 * backend закрывается, для повтора нужен новый экземпляр. [PcmAudioSource] не закрывается.
 */
class SherpaDiarizationBackend internal constructor(
    private val resource: SherpaDiarizationResource,
    private val chunked: (SherpaDiarizationResource) -> ChunkedDiarizer,
) : DiarizationBackend {
    constructor(context: Context, settings: SherpaDiarizationSettings = SherpaDiarizationSettings()) :
        this(SherpaDiarizationResource(context, settings), { resource -> defaultChunked(resource, settings) })

    private val operation = Mutex()
    private val lifecycle = Any()
    private var closed = false

    override suspend fun diarize(audio: PcmAudioSource, speech: List<SpeechSegment>): List<SpeakerSegment> =
        try {
            withContext(Dispatchers.IO) {
                operation.withLock {
                    checkOpen()
                    currentCoroutineContext().ensureActive()
                    chunked(resource).diarize(audio, speech)
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }

    private fun checkOpen() = synchronized(lifecycle) { check(!closed) { "Диаризация уже закрыта" } }

    override fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            resource.close()
        }
    }

    private companion object {
        fun defaultChunked(resource: SherpaDiarizationResource, settings: SherpaDiarizationSettings): ChunkedDiarizer {
            return ChunkedDiarizer(
                settings = ChunkedDiarizerSettings(minSimilarity = 1f - settings.clusteringThreshold),
                windowDiarizer = object : WindowDiarizer {
                    override suspend fun diarize(samples: FloatArray): List<LocalSegment> {
                        val context = currentCoroutineContext()
                        return resource.diarizeWindow(samples) { context.ensureActive() }
                    }
                },
                embedderProvider = {
                    object : SpeakerEmbedder {
                        override suspend fun embed(samples: FloatArray): FloatArray? {
                            val context = currentCoroutineContext()
                            return resource.embed(samples) { context.ensureActive() }
                        }
                    }
                },
            )
        }
    }
}
