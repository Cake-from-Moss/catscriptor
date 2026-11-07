package org.cfm.catscriptor.demo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.app.LiveLine
import org.cfm.catscriptor.domain.app.RecorderController
import org.cfm.catscriptor.domain.app.RecorderState
import org.cfm.catscriptor.domain.app.StartRecordingResult

/**
 * Демо-запись без микрофона: идёт время, уровень сигнала имитирует речь с паузами,
 * Live-текст дописывает реплики из макета слово за словом. «Стоп» ставит запись в очередь
 * [DemoProcessingController]. Все изменения состояния идут в одном потоке.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoRecorderController(
    private val store: DemoStore,
    private val processing: DemoProcessingController,
) : RecorderController {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    private var scenario = DemoScenario.DEFAULT
    private var ticker: Job? = null
    private var liveText = true
    private val random = Random(seed = 7)

    // Курсор сценария Live-текста.
    private var scriptIndex = 0
    private var revealedWords = 0
    private var wordClockMs = 0L
    private var lineSerial = 0

    override val isAvailable = true
    override val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    override suspend fun start(liveText: Boolean): StartRecordingResult = withContext(dispatcher) {
        if (scenario == DemoScenario.LOW_STORAGE) return@withContext StartRecordingResult.LowStorage(LOW_STORAGE_MINUTES)
        if (mutableState.value is RecorderState.Active) return@withContext StartRecordingResult.Started
        this@DemoRecorderController.liveText = liveText
        scriptIndex = 0
        revealedWords = 0
        wordClockMs = 0
        mutableState.value = RecorderState.Active(
            elapsedMs = 0,
            paused = false,
            levelDbfs = SILENCE_DBFS,
            peakDbfs = SILENCE_DBFS,
            speechDetected = false,
            marksMs = emptyList(),
            liveLines = emptyList(),
        )
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
        StartRecordingResult.Started
    }

    override suspend fun stop(): String? = withContext(dispatcher) {
        val active = mutableState.value as? RecorderState.Active ?: return@withContext null
        ticker?.cancel()
        ticker = null
        mutableState.value = RecorderState.Idle
        val title = "Запись " + SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ru")).format(Date())
        val text = active.liveLines.filter { it.isFinal }.joinToString(" ") { it.text }
        processing.enqueueRecording(title, active.elapsedMs.coerceAtLeast(1_000), text)
    }

    override fun pause() = mutate { it.copy(paused = true, levelDbfs = SILENCE_DBFS, peakDbfs = SILENCE_DBFS) }

    override fun resume() = mutate { it.copy(paused = false, pausedByInterruption = false) }

    override fun setLiveText(enabled: Boolean) {
        scope.launch { liveText = enabled }
    }

    override fun addMark() = mutate { it.copy(marksMs = it.marksMs + it.elapsedMs) }

    fun applyScenario(scenario: DemoScenario) {
        scope.launch {
            this@DemoRecorderController.scenario = scenario
            ticker?.cancel()
            ticker = null
            mutableState.value = RecorderState.Idle
        }
    }

    private fun mutate(transform: (RecorderState.Active) -> RecorderState.Active) {
        scope.launch {
            (mutableState.value as? RecorderState.Active)?.let { mutableState.value = transform(it) }
        }
    }

    private fun tick() {
        val active = mutableState.value as? RecorderState.Active ?: return
        if (active.paused) return
        val elapsed = active.elapsedMs + TICK_MS
        // Речь идёт фразами: 3.2 с говорим, 0.8 с пауза.
        val speaking = elapsed % PHRASE_CYCLE_MS < SPEECH_MS
        val level = if (speaking) {
            SPEECH_BASE_DBFS + 8f * sin(2 * PI * elapsed / SYLLABLE_MS).toFloat() + random.nextFloat() * 8f - 4f
        } else {
            SILENCE_DBFS + random.nextFloat() * 4f
        }
        // Редкий громкий выкрик, чтобы в демо была видна подпись о клиппинге.
        val peak = if (elapsed % CLIP_EVERY_MS < TICK_MS * 3) -1.5f else level + 4f + random.nextFloat() * 4f
        val lines = if (liveText && speaking) advanceScript(active.liveLines) else active.liveLines
        mutableState.value = active.copy(
            elapsedMs = elapsed,
            levelDbfs = level,
            peakDbfs = peak,
            speechDetected = speaking,
            liveLines = lines,
        )
    }

    /**
     * Гипотеза модели приходит без пунктуации и в нижнем регистре, как у потоковой ASR;
     * когда фраза дописана, она заменяется окончательным текстом.
     */
    private fun advanceScript(lines: List<LiveLine>): List<LiveLine> {
        wordClockMs += TICK_MS
        if (wordClockMs < WORD_MS) return lines
        wordClockMs = 0
        val elapsed = (mutableState.value as RecorderState.Active).elapsedMs
        val entry = SCRIPT[scriptIndex % SCRIPT.size]
        val words = entry.text.split(' ')
        val last = lines.lastOrNull()
        return if (last == null || last.isFinal) {
            revealedWords = 1
            lines + LiveLine(
                id = "live-${lineSerial++}",
                speakerId = entry.speakerId,
                startMs = elapsed,
                text = hypothesis(words, revealedWords),
                isFinal = false,
            )
        } else if (revealedWords < words.size) {
            revealedWords++
            lines.dropLast(1) + last.copy(text = hypothesis(words, revealedWords))
        } else {
            scriptIndex++
            lines.dropLast(1) + last.copy(text = entry.text, isFinal = true)
        }
    }

    private fun hypothesis(words: List<String>, count: Int): String =
        words.take(count).joinToString(" ") { it.trim(',', '.', '?', '!', ':').lowercase() }

    private data class ScriptLine(val speakerId: String, val text: String)

    private companion object {
        const val TICK_MS = 100L
        const val PHRASE_CYCLE_MS = 4_000L
        const val SPEECH_MS = 3_200L
        const val SYLLABLE_MS = 260.0
        const val WORD_MS = 300L
        const val CLIP_EVERY_MS = 17_000L
        const val SPEECH_BASE_DBFS = -24f
        const val SILENCE_DBFS = -58f
        const val LOW_STORAGE_MINUTES = 7

        val SCRIPT = listOf(
            ScriptLine("speaker_1", "Итак, по распознаванию: Whisper small в int8 укладывается в память на тестовом телефоне."),
            ScriptLine("speaker_3", "Да, но на длинных записях он начинает отставать от реального времени."),
            ScriptLine("speaker_1", "Поэтому для live берём потоковую модель, а финальный текст пересчитываем после остановки."),
            ScriptLine("speaker_2", "Согласна. Диаризацию тоже делаем в конце?"),
            ScriptLine("speaker_1", "Да, в live спикеры предварительные."),
            ScriptLine("speaker_2", "А по тестам у нас что сейчас со временем обработки?"),
        )
    }
}
