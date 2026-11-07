package org.cfm.catscriptor.domain.pipeline

/**
 * Слова принадлежат этому спикеру и сохраняют исходную привязку ASR к аудио после коррекции.
 * Локальная LLM может исправить [text]; он не обязан дословно совпадать с исходными словами.
 * Исправленный текст нельзя представлять как результат с новой пословной привязкой к аудио.
 */
data class Utterance(
    val speakerId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<TimedWord>,
) {
    init {
        requireSpeakerId(speakerId)
        val time = TimeRange(startMs, endMs)
        require(words.isNotEmpty()) { "An utterance must retain its source words" }
        require(words.all { time.contains(it.time) }) { "Words must lie inside the utterance" }
        require(words.zipWithNext().all { (a, b) -> a.time.startMs <= b.time.startMs }) {
            "Words must be ordered by start time"
        }
    }
}

/** Экспортируются только speakerId/startMs/endMs/text; внутренняя привязка слов не входит в JSON. */
data class Transcript(val transcript: List<Utterance>) {
    init {
        require(transcript.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }) {
            "Utterances must be ordered by start time; overlaps are allowed"
        }
    }
}
