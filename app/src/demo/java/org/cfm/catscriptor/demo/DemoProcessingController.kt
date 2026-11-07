package org.cfm.catscriptor.demo

import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.app.ImportResult
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.domain.app.ProcessingController
import org.cfm.catscriptor.domain.app.ProcessingJob
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.SpeakerInfo
import org.cfm.catscriptor.domain.app.StageProgress
import org.cfm.catscriptor.domain.app.StageStatus
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.TranscriptLine

/**
 * Демо-очередь обработки: ведёт «Планёрку 06.11» из макета от 64 % до готовности и обрабатывает
 * новые импорты и записи. Время симулируется тикером; все изменения идут в одном потоке.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoProcessingController(private val store: DemoStore) : ProcessingController {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val jobs = MutableStateFlow<List<ProcessingJob>>(emptyList())
    private val models = MutableStateFlow<ModelsStatus>(ModelsStatus.Ready)
    private val queue = mutableListOf<SimJob>()
    private var nextId = 1

    override val modelsStatus: StateFlow<ModelsStatus> = models.asStateFlow()

    init {
        applyScenario(DemoScenario.DEFAULT)
        scope.launch {
            while (true) {
                delay(TICK_MS)
                tick((TICK_MS * TIME_SCALE).toLong())
            }
        }
    }

    override fun observeJobs(): Flow<List<ProcessingJob>> = jobs.asStateFlow()

    override suspend fun importFile(documentUri: String, displayName: String): ImportResult = withContext(dispatcher) {
        if (models.value is ModelsStatus.Unavailable) return@withContext ImportResult.ModelsUnavailable
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        // Системный выбор уже отфильтровал audio/*; явно чужое расширение считаем неподдерживаемым.
        if (extension.isNotEmpty() && extension !in SUPPORTED_EXTENSIONS) {
            return@withContext ImportResult.UnsupportedFormat
        }
        val title = if (extension.isEmpty()) displayName else displayName.substringBeforeLast('.')
        // Декодера в демо нет, поэтому длительность выводится из имени: стабильно для одного файла.
        val durationMs = MIN_IMPORT_MS + (displayName.hashCode().toLong() and 0xFFFF) % IMPORT_SPREAD_MS
        val id = enqueue(
            title = title,
            source = RecordingSource.FILE,
            fileExtension = extension.ifEmpty { null },
            durationMs = durationMs,
            text = IMPORT_TEXT,
        )
        ImportResult.Accepted(id)
    }

    /** Ставит в очередь только что остановленную запись с микрофона; возвращает её id. */
    suspend fun enqueueRecording(title: String, durationMs: Long, text: String): String = withContext(dispatcher) {
        enqueue(
            title = title,
            source = RecordingSource.MICROPHONE,
            fileExtension = null,
            durationMs = durationMs,
            text = text.ifBlank { IMPORT_TEXT },
        )
    }

    override suspend fun cancel(recordingId: String) = withContext(dispatcher) {
        queue.removeAll { it.id == recordingId }
        store.summaries.update { list -> list.filterNot { it.id == recordingId } }
        publish()
    }

    override suspend fun retry(recordingId: String) = withContext(dispatcher) {
        queue.firstOrNull { it.id == recordingId }?.let { job ->
            job.failure = null
            job.stageElapsedMs = 0
        }
        publish()
    }

    override suspend fun skipStage(recordingId: String, stage: ProcessingStage) = withContext(dispatcher) {
        queue.firstOrNull { it.id == recordingId }?.let { job ->
            if (job.currentStage == stage) {
                job.failure = null
                job.skipped += stage
                job.advance()
            }
        }
        if (queue.firstOrNull()?.isFinished == true) finish(queue.removeAt(0))
        publish()
    }

    /**
     * Сброс библиотеки идёт в том же последовательном потоке, что и сценарий: иначе отложенный
     * запуск сценария по умолчанию из init мог вернуть «Планёрку» уже после сброса.
     */
    fun applyScenario(scenario: DemoScenario) {
        scope.launch {
            store.reset(scenario)
            queue.clear()
            models.value = if (scenario == DemoScenario.MODELS_MISSING) {
                ModelsStatus.Unavailable(MODELS_MISSING_DETAILS)
            } else {
                ModelsStatus.Ready
            }
            when (scenario) {
                DemoScenario.DEFAULT, DemoScenario.LOW_STORAGE -> queue += planerka()
                DemoScenario.PROCESSING_ERROR -> queue += planerka().apply {
                    stageIndex = ProcessingStage.CORRECT_TEXT.ordinal
                    ProcessingStage.entries.take(stageIndex).forEach { done[it] = stageDurations.getValue(it) }
                    failure = LLM_FAILURE
                }
                // Модели недоступны: запись лежит в библиотеке и ждёт, обработка не идёт.
                DemoScenario.MODELS_MISSING -> ensureSummary(planerka(), RecordingStatus.Queued)
                DemoScenario.EMPTY -> Unit
            }
            publish()
        }
    }

    private fun planerka() = SimJob(
        id = DemoSeed.PROCESSING_ID,
        title = "Планёрка 06.11",
        source = RecordingSource.FILE,
        fileExtension = "m4a",
        durationMs = PLANERKA_DURATION_MS,
        text = PLANERKA_TEXT,
        // Подобрано под кадр макета: на 0:41 распознавания обработано 8:10 из 12:41, это 64 %.
        stageDurations = mapOf(
            ProcessingStage.PREPARE_AUDIO to 4_000L,
            ProcessingStage.DETECT_SPEECH to 2_000L,
            ProcessingStage.TRANSCRIBE to 63_650L,
            ProcessingStage.DIARIZE to 6_000L,
            ProcessingStage.CORRECT_TEXT to 5_000L,
        ),
    ).apply {
        done[ProcessingStage.PREPARE_AUDIO] = 4_000L
        done[ProcessingStage.DETECT_SPEECH] = 2_000L
        stageIndex = ProcessingStage.TRANSCRIBE.ordinal
        stageElapsedMs = 41_000L
    }

    private fun enqueue(
        title: String,
        source: RecordingSource,
        fileExtension: String?,
        durationMs: Long,
        text: String,
    ): String {
        val id = "demo-${System.currentTimeMillis()}-${nextId++}"
        val job = SimJob(
            id = id,
            title = title,
            source = source,
            fileExtension = fileExtension,
            durationMs = durationMs,
            text = text,
            stageDurations = mapOf(
                ProcessingStage.PREPARE_AUDIO to 1_500L,
                ProcessingStage.DETECT_SPEECH to 1_000L,
                ProcessingStage.TRANSCRIBE to maxOf(4_000L, durationMs / 12),
                ProcessingStage.DIARIZE to maxOf(3_000L, durationMs / 60),
                ProcessingStage.CORRECT_TEXT to maxOf(2_500L, durationMs / 80),
            ),
        )
        if (models.value is ModelsStatus.Unavailable) {
            ensureSummary(job, RecordingStatus.Queued)
        } else {
            queue += job
            publish()
        }
        return id
    }

    private fun tick(simMs: Long) {
        val head = queue.firstOrNull() ?: return
        if (head.failure != null || models.value is ModelsStatus.Unavailable) return
        head.stageElapsedMs += simMs
        if (head.stageElapsedMs >= head.stageDurations.getValue(head.currentStage)) head.advance()
        if (head.isFinished) finish(queue.removeAt(0))
        publish()
    }

    private fun publish() {
        jobs.value = queue.mapIndexed { index, job -> job.snapshot(active = index == 0) }
        queue.forEachIndexed { index, job ->
            val status = when {
                index > 0 -> RecordingStatus.Queued
                job.failure != null -> RecordingStatus.Failed(job.currentStage)
                else -> RecordingStatus.Processing(job.fraction(), job.currentStage)
            }
            ensureSummary(job, status)
        }
    }

    /** Обновляет строку библиотеки; если её нет (например, сид пуст), создаёт. */
    private fun ensureSummary(job: SimJob, status: RecordingStatus) {
        if (store.summaries.value.any { it.id == job.id }) {
            store.updateSummary(job.id) { it.copy(status = status) }
        } else {
            store.upsert(
                RecordingSummary(
                    id = job.id,
                    title = job.title,
                    source = job.source,
                    createdAtEpochMs = job.createdAtEpochMs,
                    durationMs = job.durationMs,
                    speakerCount = null,
                    previewSpeakerId = null,
                    previewSpeakerName = null,
                    previewText = null,
                    status = status,
                ),
            )
        }
    }

    private fun finish(job: SimJob) {
        val document = store.documents.value[job.id] ?: job.toDocument().also { doc ->
            store.documents.update { it + (job.id to doc) }
        }
        val first = document.lines.firstOrNull()
        ensureSummary(job, RecordingStatus.Ready)
        store.updateSummary(job.id) {
            it.copy(
                status = RecordingStatus.Ready,
                speakerCount = document.speakers.size,
                previewSpeakerId = first?.speakerId,
                previewText = first?.text,
            )
        }
    }

    /** Состояние одной симулируемой задачи; меняется только в потоке [dispatcher]. */
    private class SimJob(
        val id: String,
        val title: String,
        val source: RecordingSource,
        val fileExtension: String?,
        val durationMs: Long,
        val text: String,
        val stageDurations: Map<ProcessingStage, Long>,
        val createdAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        var stageIndex = 0
        var stageElapsedMs = 0L
        var failure: String? = null
        val done = mutableMapOf<ProcessingStage, Long>()
        val skipped = mutableSetOf<ProcessingStage>()

        val isFinished: Boolean get() = stageIndex >= STAGES.size
        val currentStage: ProcessingStage get() = STAGES[stageIndex.coerceAtMost(STAGES.lastIndex)]

        fun advance() {
            if (currentStage !in skipped) done[currentStage] = stageElapsedMs.coerceAtMost(stageDurations.getValue(currentStage))
            stageIndex++
            stageElapsedMs = 0
        }

        /** Доля по весам этапов: распознавание занимает почти всю работу. */
        fun fraction(): Float {
            var total = 0f
            STAGES.forEachIndexed { index, stage ->
                val weight = STAGE_WEIGHTS.getValue(stage)
                total += when {
                    stage in done || stage in skipped -> weight
                    index == stageIndex -> weight * stageProgress()
                    else -> 0f
                }
            }
            return total.coerceIn(0f, 1f)
        }

        private fun stageProgress(): Float =
            (stageElapsedMs.toFloat() / stageDurations.getValue(currentStage)).coerceIn(0f, 1f)

        private fun remainingWallMs(): Long {
            val left = STAGES.drop(stageIndex).sumOf { stageDurations.getValue(it) } - stageElapsedMs
            return (left.coerceAtLeast(0) / TIME_SCALE).toLong()
        }

        private fun transcribedText(): String {
            val transcribeIndex = ProcessingStage.TRANSCRIBE.ordinal
            val words = text.split(' ')
            val share = when {
                stageIndex > transcribeIndex -> 1f
                stageIndex < transcribeIndex -> 0f
                else -> stageProgress()
            }
            return words.take(ceil(words.size * share).toInt()).joinToString(" ")
        }

        fun snapshot(active: Boolean): ProcessingJob = ProcessingJob(
            recordingId = id,
            title = title,
            source = source,
            fileExtension = fileExtension,
            durationMs = durationMs,
            overallFraction = if (active) fraction() else 0f,
            stages = STAGES.mapIndexed { index, stage ->
                val status = when {
                    stage in skipped -> StageStatus.Skipped
                    stage in done -> StageStatus.Done(done.getValue(stage))
                    !active || index != stageIndex -> StageStatus.Pending
                    failure != null -> StageStatus.Failed(failure)
                    stage == ProcessingStage.TRANSCRIBE -> StageStatus.Running(
                        elapsedMs = stageElapsedMs,
                        processedMs = (durationMs * stageProgress()).toLong(),
                    )
                    else -> StageStatus.Running(stageElapsedMs)
                }
                StageProgress(stage, status)
            },
            remainingMs = if (active && failure == null) remainingWallMs() else null,
            partialText = if (active) transcribedText() else "",
        )

        /** Готовая расшифровка для демо: предложения по очереди у двух спикеров. */
        fun toDocument(): TranscriptDocument {
            val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            val step = durationMs / sentences.size.coerceAtLeast(1)
            val lines = sentences.mapIndexed { index, sentence ->
                TranscriptLine(
                    id = "$id-line-$index",
                    speakerId = "speaker_${index % 2 + 1}",
                    startMs = index * step,
                    endMs = (index + 1) * step - 200,
                    text = sentence,
                )
            }
            val speakers = lines.groupBy { it.speakerId }.map { (speakerId, own) ->
                SpeakerInfo(speakerId, displayName = null, shareFraction = own.size.toFloat() / lines.size)
            }
            return TranscriptDocument(
                recordingId = id,
                title = title,
                createdAtEpochMs = createdAtEpochMs,
                durationMs = durationMs,
                speakers = speakers,
                lines = lines,
            )
        }
    }

    private companion object {
        val STAGES = ProcessingStage.entries

        // Веса подобраны так, чтобы кадр макета (8:10 из 12:41 на распознавании) давал 64 %.
        val STAGE_WEIGHTS = mapOf(
            ProcessingStage.PREPARE_AUDIO to 0.04f,
            ProcessingStage.DETECT_SPEECH to 0.04f,
            ProcessingStage.TRANSCRIBE to 0.87f,
            ProcessingStage.DIARIZE to 0.03f,
            ProcessingStage.CORRECT_TEXT to 0.02f,
        )

        const val TICK_MS = 200L

        // Демо идёт вдвое медленнее реального времени, чтобы успеть рассмотреть этапы.
        const val TIME_SCALE = 0.5f

        const val PLANERKA_DURATION_MS = 761_000L
        const val MIN_IMPORT_MS = 120_000L
        const val IMPORT_SPREAD_MS = 780_000L

        val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "3gp", "webm", "mp4")

        const val LLM_FAILURE = "Не удалось загрузить локальную LLM: недостаточно памяти"
        const val MODELS_MISSING_DETAILS =
            "Не найдены файлы GigaAM v3 в assets/models. Без них запись сохраняется, но не расшифровывается."

        const val PLANERKA_TEXT =
            "Коротко по плану на неделю. Сборку отдаём организаторам в понедельник, значит, к понедельнику " +
                "нужен APK для проверки. Модели кладём в assets, чтобы всё работало без интернета. " +
                "По метрикам смотрим WER и RTF на трёх тестовых записях. Диаризацию проверяем на диалогах, " +
                "где спикеры перебивают друг друга. Если останется время, доделаем экспорт в SRT."

        const val IMPORT_TEXT =
            "Добрый день, начинаем. Сегодня обсуждаем результаты тестов и сроки. По скорости мы укладываемся, " +
                "а по качеству распознавания ещё есть над чем поработать. Давайте зафиксируем следующие шаги."
    }
}
