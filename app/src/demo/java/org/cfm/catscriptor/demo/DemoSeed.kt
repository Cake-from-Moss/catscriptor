package org.cfm.catscriptor.demo

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.cfm.catscriptor.domain.app.ProcessingStage
import org.cfm.catscriptor.domain.app.RecordingSource
import org.cfm.catscriptor.domain.app.RecordingStatus
import org.cfm.catscriptor.domain.app.RecordingSummary
import org.cfm.catscriptor.domain.app.TranscriptDocument
import org.cfm.catscriptor.domain.app.TranscriptLine
import org.cfm.catscriptor.domain.app.computeSpeakers
import org.cfm.catscriptor.domain.app.lineId

/**
 * Данные кадров макета: главный экран, список записей и расшифровка «Созвон с заказчиком».
 * Даты считаются от текущего дня, чтобы группы «Сегодня», «Вчера» и месяц выглядели как в макете.
 * Только для демо-сборки: в основной сборке выдуманных данных нет.
 */
object DemoSeed {
    /** Запись «Планёрка 06.11» из макета: библиотека показывает её, контроллер обработки ведёт. */
    const val PROCESSING_ID = "planerka"
    const val CALL_ID = "sozvon"
    const val INTERVIEW_ID = "interview-03"
    const val LECTURE_ID = "lecture-os"
    const val NOTE_ID = "voice-note"
    const val PODCAST_ID = "podcast-12"

    private const val PROCESSING_DURATION_MS = 12 * 60_000L + 41_000L

    fun summaries(): List<RecordingSummary> {
        val docs = documents()
        fun ready(id: String, source: RecordingSource): RecordingSummary {
            val doc = docs.getValue(id)
            val preview = previewLine(id, doc)
            return RecordingSummary(
                id = id,
                title = doc.title,
                source = source,
                createdAtEpochMs = doc.createdAtEpochMs,
                durationMs = doc.durationMs,
                speakerCount = doc.speakers.size,
                previewSpeakerId = preview.speakerId,
                previewSpeakerName = doc.speakers.firstOrNull { it.id == preview.speakerId }?.displayName,
                previewText = preview.text,
                status = RecordingStatus.Ready,
            )
        }
        return listOf(
            RecordingSummary(
                id = PROCESSING_ID,
                title = "Планёрка 06.11",
                source = RecordingSource.FILE,
                createdAtEpochMs = at(daysAgo = 0, hour = 10, minute = 42),
                durationMs = PROCESSING_DURATION_MS,
                speakerCount = null,
                previewSpeakerId = null,
                previewSpeakerName = null,
                previewText = null,
                status = RecordingStatus.Processing(0.64f, ProcessingStage.TRANSCRIBE),
            ),
            ready(CALL_ID, RecordingSource.MICROPHONE),
            ready(INTERVIEW_ID, RecordingSource.FILE),
            ready(LECTURE_ID, RecordingSource.MICROPHONE),
            ready(NOTE_ID, RecordingSource.MICROPHONE),
            ready(PODCAST_ID, RecordingSource.FILE),
        )
    }

    fun documents(): Map<String, TranscriptDocument> = listOf(
        document(
            id = CALL_ID,
            title = "Созвон с заказчиком",
            createdAt = at(daysAgo = 0, hour = 9, minute = 15),
            durationMs = mmss(23, 8),
            names = mapOf("speaker_1" to "Анна"),
            lines = callLines(),
        ),
        document(
            id = INTERVIEW_ID,
            title = "Интервью_03.m4a",
            createdAt = at(daysAgo = 1, hour = 18, minute = 30),
            durationMs = mmss(41, 52),
            lines = listOf(
                Line(1, mmss(0, 3), mmss(0, 7), "Расскажите, как вы пришли в профессию?"),
                Line(2, mmss(0, 8), mmss(0, 20), "Случайно: на третьем курсе попал на практику в лабораторию звука."),
                Line(1, mmss(0, 21), mmss(0, 25), "Что было самым сложным в первые годы?"),
                Line(2, mmss(0, 26), mmss(0, 48), "Научиться слушать заказчика, а не только записи."),
                Line(1, mmss(0, 50), mmss(0, 58), "А сейчас чем занимаетесь?"),
                Line(2, mmss(1, 0), mmss(41, 40), "Автоматической расшифровкой совещаний прямо на телефоне."),
            ),
        ),
        document(
            id = LECTURE_ID,
            title = "Лекция по ОС",
            createdAt = at(daysAgo = 1, hour = 12, minute = 0),
            durationMs = 78 * 60_000L + 40_000L,
            lines = listOf(
                Line(1, mmss(0, 5), mmss(0, 12), "Планировщик выбирает следующий процесс по приоритету."),
                Line(2, mmss(0, 14), mmss(0, 17), "А если приоритеты равны?"),
                Line(1, mmss(0, 18), mmss(0, 30), "Тогда по очереди: это круговой алгоритм."),
                Line(3, mmss(0, 32), mmss(0, 36), "Можно пример с квантом времени?"),
                Line(1, mmss(0, 38), mmss(78, 20), "Конечно. Возьмём квант в десять миллисекунд и три процесса."),
            ),
        ),
        document(
            id = NOTE_ID,
            title = "Голосовая заметка",
            createdAt = at(daysAgo = 4, hour = 8, minute = 20),
            durationMs = mmss(2, 14),
            lines = listOf(
                Line(1, mmss(0, 1), mmss(0, 9), "Купить батарейки для диктофона и проверить микрофон перед встречей."),
                Line(1, mmss(0, 40), mmss(2, 10), "Перенести созвон с дизайнерами на пятницу."),
            ),
        ),
        document(
            id = PODCAST_ID,
            title = "Подкаст_эп12.mp3",
            createdAt = at(daysAgo = 6, hour = 19, minute = 5),
            durationMs = mmss(54, 3),
            lines = listOf(
                Line(1, mmss(0, 2), mmss(0, 9), "Привет! Это двенадцатый выпуск, и сегодня говорим о распознавании речи."),
                Line(2, mmss(0, 10), mmss(0, 14), "Привет всем! Я впервые у вас в гостях."),
                Line(3, mmss(0, 15), mmss(0, 19), "А я, как обычно, задаю неудобные вопросы."),
                Line(1, mmss(0, 20), mmss(53, 50), "Начнём с главного: может ли телефон расшифровать запись без интернета?"),
            ),
        ),
    ).associateBy { it.recordingId }

    /**
     * Первые шесть реплик и таймкоды взяты из кадра расшифровки и предпросмотра JSON
     * (4120–6380 мс). Остальные растягивают разговор до 23:08; длительности подобраны так,
     * чтобы доли спикеров совпали с макетом: Анна 54 %, Спикер 2 46 %.
     */
    private fun callLines(): List<Line> = listOf(
        Line(1, 4_120, 6_380, "Добрый день! Слышите меня нормально?"),
        Line(2, 7_000, 10_200, "Да, всё отлично слышно. Начнём?"),
        Line(1, 11_000, 23_400, "Давайте. Мы подготовили прототип: импорт файла, расшифровка и экспорт уже работают."),
        Line(2, 24_100, 30_500, "Отлично. А как с разделением говорящих на длинных записях?"),
        Line(1, 31_000, 44_200, "Держится стабильно, мы сравниваем голоса по эмбеддингам на всём файле, а не кусками."),
        Line(2, 45_000, 51_300, "Хорошо. Давайте зафиксируем сроки по второму этапу."),
        Line(1, mmss(0, 52), mmss(2, 30), "Второй этап — запись с микрофона и Live-текст. Думаю, нам хватит двух недель."),
        Line(2, mmss(2, 32), mmss(4, 40), "Две недели подходят. Что с качеством распознавания на шумных записях?"),
        Line(1, mmss(4, 42), mmss(6, 50), "На тестовом наборе ошибка около девяти процентов. В шуме хуже, поэтому перед распознаванием чистим звук."),
        Line(2, mmss(6, 52), mmss(8, 45), "А где обрабатывается звук? Нам важно, чтобы записи не уходили наружу."),
        Line(1, mmss(8, 47), mmss(10, 40), "Всё на телефоне: модели лежат внутри приложения, сеть для обработки не нужна."),
        Line(2, mmss(10, 42), mmss(12, 40), "Это снимает вопросы безопасности. Сколько времени занимает час записи?"),
        Line(1, mmss(12, 52), mmss(15, 10), "На среднем телефоне около двенадцати минут. Приложение можно свернуть, обработка продолжится в фоне."),
        Line(2, mmss(15, 12), mmss(16, 45), "Понятно. А экспорт в каких форматах?"),
        Line(1, mmss(16, 57), mmss(19, 5), "Текст, JSON и субтитры SRT. В JSON у каждой реплики спикер и таймкоды в миллисекундах."),
        Line(2, mmss(19, 7), mmss(20, 25), "Нам как раз нужен JSON для системы протоколов. Имена спикеров можно поменять?"),
        Line(1, mmss(20, 32), mmss(21, 40), "Да, нажимаете на чип спикера и вводите имя, оно применится ко всем репликам."),
        Line(2, mmss(21, 42), mmss(22, 30), "Хорошо. Тогда следующая встреча через две недели, покажете запись с микрофона."),
        Line(1, mmss(22, 40), mmss(22, 50), "Договорились. Пришлю приглашение и короткий отчёт по сегодняшнему созвону."),
        Line(2, mmss(22, 51), mmss(22, 58), "Спасибо, до связи!"),
        Line(1, mmss(23, 0), mmss(23, 6), "До свидания!"),
    )

    /** Блок «Недавние» в макете показывает для созвона реплику про сроки, для остальных — первую. */
    private fun previewLine(id: String, doc: TranscriptDocument): TranscriptLine =
        if (id == CALL_ID) doc.lines[5].copy(text = "Давайте зафиксируем сроки по второму этапу.") else doc.lines.first()

    private data class Line(val speaker: Int, val startMs: Long, val endMs: Long, val text: String)

    private fun document(
        id: String,
        title: String,
        createdAt: Long,
        durationMs: Long,
        lines: List<Line>,
        names: Map<String, String> = emptyMap(),
    ): TranscriptDocument {
        val transcriptLines = lines.mapIndexed { index, line ->
            TranscriptLine(lineId(index, line.startMs), "speaker_${line.speaker}", line.startMs, line.endMs, line.text)
        }
        return TranscriptDocument(
            recordingId = id,
            title = title,
            createdAtEpochMs = createdAt,
            durationMs = durationMs,
            speakers = computeSpeakers(transcriptLines, names),
            lines = transcriptLines,
        )
    }

    private fun mmss(minutes: Int, seconds: Int): Long = minutes * 60_000L + seconds * 1000L

    /**
     * Время [daysAgo] дней назад в заданные часы. Сегодняшнее время из будущего (демо открыли
     * рано утром) сдвигается на минуту назад от текущего момента, но остаётся в пределах суток.
     */
    private fun at(daysAgo: Long, hour: Int, minute: Int): Long {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val day = LocalDate.now(zone).minusDays(daysAgo)
        val value = day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
        if (daysAgo > 0 || value <= now) return value
        val startOfDay = day.atStartOfDay(zone).toInstant().toEpochMilli()
        return maxOf(startOfDay, now - 60_000L)
    }
}
