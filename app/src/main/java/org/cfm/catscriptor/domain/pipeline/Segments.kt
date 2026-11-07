package org.cfm.catscriptor.domain.pipeline

/** Полуоткрытый интервал в миллисекундах от начала исходной записи. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0 && endMs > startMs) { "Expected 0 <= startMs < endMs" }
    }

    fun contains(other: TimeRange): Boolean =
        startMs <= other.startMs && endMs >= other.endMs
}

/** Исходное слово ASR и его привязка к аудио; LLM не заменяет его временные метки. */
data class TimedWord(val text: String, val time: TimeRange) {
    init {
        require(text.isNotBlank()) { "A timed word must contain text" }
    }
}

/** Речь, обнаруженная VAD; временные метки отсчитываются от начала записи. */
data class SpeechSegment(val time: TimeRange)

/** ASR должен выдавать привязку слов к аудио, а не равномерно делить интервал между словами. */
data class AsrSegment(val time: TimeRange, val words: List<TimedWord>) {
    init {
        require(words.all { time.contains(it.time) }) { "Words must lie inside the segment" }
        require(words.zipWithNext().all { (a, b) -> a.time.startMs <= b.time.startMs }) {
            "Words must be ordered by start time"
        }
    }
}

/** Интервалы могут перекрываться, в том числе у разных спикеров. */
data class SpeakerSegment(val speakerId: String, val time: TimeRange) {
    init {
        requireSpeakerId(speakerId)
    }
}

internal fun requireSpeakerId(value: String) {
    require(Regex("speaker_[1-9][0-9]*").matches(value)) { "Expected speaker_N with N >= 1" }
}
