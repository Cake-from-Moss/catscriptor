package org.cfm.catscriptor.domain.diarization

import org.cfm.catscriptor.domain.pipeline.SpeechSegment
import org.cfm.catscriptor.domain.pipeline.TimeRange

/**
 * Делит запись на окна ограниченной длины для нативной диаризации.
 *
 * Ограничение окна вытекает из sherpa-onnx 2.0.1: аудио передаётся одним массивом, а кластеризация
 * строит матрицу попарных расстояний по всем парам «окно сегментации × спикер», то есть память
 * растёт квадратично с длиной записи. Окно режется по паузам между сегментами VAD; если один
 * сегмент VAD длиннее окна, он делится жёстко (спикер на стыке сопоставляется по эмбеддингам).
 *
 * @property maxWindowMs предельная длина окна.
 * @property paddingMs запас тишины вокруг речи, чтобы модель видела начало и конец реплик.
 */
class DiarizationWindowPlanner(
    private val maxWindowMs: Long = 480_000,
    private val paddingMs: Long = 500,
) {
    init {
        require(maxWindowMs > 2 * paddingMs && paddingMs >= 0)
    }

    /** Окна упорядочены, не пересекаются и не выходят за [0, durationMs]. Нет речи — нет окон. */
    fun plan(speech: List<SpeechSegment>, durationMs: Long): List<TimeRange> {
        val ranges = speech.map { it.time }.sortedBy { it.startMs }
            .mapNotNull { clamp(it, durationMs) }
        if (ranges.isEmpty()) return emptyList()
        val windows = ArrayList<TimeRange>()
        var start = -1L
        var end = -1L
        fun flush() {
            if (start >= 0) windows += TimeRange(maxOf(0L, start - paddingMs), minOf(durationMs, end + paddingMs))
            start = -1L
        }
        for (range in ranges) {
            var from = range.startMs
            while (range.endMs - from > maxWindowMs - 2 * paddingMs) {
                // Единственный сегмент не помещается: закрываем окно и режем сегмент жёстко.
                flush()
                val cut = from + (maxWindowMs - 2 * paddingMs)
                start = from; end = cut
                flush()
                from = cut
            }
            if (start >= 0 && range.endMs - start > maxWindowMs - 2 * paddingMs) flush()
            if (start < 0) start = from
            end = maxOf(end, range.endMs)
        }
        flush()
        return resolveOverlaps(windows)
    }

    private fun clamp(range: TimeRange, durationMs: Long): TimeRange? {
        val end = minOf(range.endMs, durationMs)
        return if (end > range.startMs) TimeRange(range.startMs, end) else null
    }

    /** Запас тишины соседних окон не должен накладываться: сдвигаем границу на середину паузы. */
    private fun resolveOverlaps(windows: List<TimeRange>): List<TimeRange> {
        val result = windows.toMutableList()
        for (i in 1 until result.size) {
            val prev = result[i - 1]
            val cur = result[i]
            if (cur.startMs < prev.endMs) {
                val middle = (cur.startMs + prev.endMs) / 2
                result[i - 1] = TimeRange(prev.startMs, middle)
                result[i] = TimeRange(middle, cur.endMs)
            }
        }
        return result
    }
}
