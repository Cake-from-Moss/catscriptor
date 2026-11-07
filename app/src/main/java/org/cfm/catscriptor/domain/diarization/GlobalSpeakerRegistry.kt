package org.cfm.catscriptor.domain.diarization

import kotlin.math.sqrt

/** Эмбеддинг локального спикера окна и длительность речи, по которой он вычислен. */
class LocalSpeakerEmbedding(val vector: FloatArray, val speechMs: Long)

/**
 * Сопоставляет локальных спикеров каждого окна с глобальными по косинусной близости эмбеддингов.
 *
 * Глобальный спикер хранит центроид — взвешенное по длительности речи среднее нормированных
 * эмбеддингов. Внутри одного окна два разных локальных спикера не могут стать одним глобальным:
 * кластеризация окна уже признала их разными. Пары назначаются жадно по убыванию близости.
 *
 * @property minSimilarity минимальная косинусная близость, при которой голос признаётся уже известным.
 *   Соответствует порогу расстояния `1 - minSimilarity` у кластеризации sherpa (по умолчанию 0,5).
 */
class GlobalSpeakerRegistry(private val minSimilarity: Float = 0.5f) {
    private class Centroid(var sum: DoubleArray, var weight: Double)

    private val centroids = ArrayList<Centroid>()

    val size: Int get() = centroids.size

    /**
     * Возвращает глобальные индексы для локальных спикеров окна (в том же порядке).
     * Спикеры с речью короче [newSpeakerMinMs] не создают нового глобального спикера:
     * их эмбеддинг ненадёжен, поэтому они присоединяются к ближайшему известному.
     */
    fun assign(locals: List<LocalSpeakerEmbedding>, newSpeakerMinMs: Long = 1_000): List<Int> {
        val normalized = locals.map { normalize(it.vector) }
        val similarities = locals.indices.flatMap { l ->
            centroids.indices.map { g -> Triple(l, g, cosine(normalized[l], centroids[g])) }
        }.sortedWith(compareByDescending<Triple<Int, Int, Double>> { it.third }.thenBy { it.second }.thenBy { it.first })

        val result = IntArray(locals.size) { -1 }
        val used = HashSet<Int>()
        for ((l, g, similarity) in similarities) {
            if (result[l] >= 0 || g in used) continue
            val reliable = locals[l].speechMs >= newSpeakerMinMs
            if (similarity >= minSimilarity || (!reliable && similarity > -1.0)) {
                result[l] = g
                used += g
            }
        }
        for (l in locals.indices) {
            if (result[l] >= 0) continue
            val global = if (locals[l].speechMs < newSpeakerMinMs && centroids.isNotEmpty()) {
                // Ненадёжный голос: ближайший из неиспользованных в окне, иначе ближайший вообще.
                centroids.indices.maxByOrNull { cosine(normalized[l], centroids[it]) }!!
            } else {
                centroids += Centroid(DoubleArray(normalized[l].size), 0.0)
                centroids.lastIndex
            }
            result[l] = global
            used += global
        }
        for (l in locals.indices) update(centroids[result[l]], normalized[l], locals[l].speechMs.toDouble().coerceAtLeast(1.0))
        return result.toList()
    }

    private fun update(centroid: Centroid, vector: DoubleArray, weight: Double) {
        for (i in vector.indices) centroid.sum[i] += vector[i] * weight
        centroid.weight += weight
    }

    private fun normalize(vector: FloatArray): DoubleArray {
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Некорректный эмбеддинг" }
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        require(norm > 0) { "Нулевой эмбеддинг" }
        return DoubleArray(vector.size) { vector[it] / norm }
    }

    private fun cosine(vector: DoubleArray, centroid: Centroid): Double {
        require(vector.size == centroid.sum.size) { "Размерность эмбеддинга изменилась" }
        if (centroid.weight == 0.0) return -1.0
        var dot = 0.0
        var norm = 0.0
        for (i in vector.indices) {
            dot += vector[i] * centroid.sum[i]
            norm += centroid.sum[i] * centroid.sum[i]
        }
        return if (norm == 0.0) -1.0 else dot / sqrt(norm)
    }
}
