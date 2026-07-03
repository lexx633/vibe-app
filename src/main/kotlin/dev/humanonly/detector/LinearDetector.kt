package dev.humanonly.detector

import kotlin.math.exp

/**
 * Линейный инференс детектора: score = sigmoid(dot(weights, features) + bias).
 *
 * Модель линейная (см. data-model.md §10, architecture.md). Скор ∈ (0,1) — вероятность AI;
 * пороги (high/low) применяются выше по каскаду, здесь только чистый инференс.
 *
 * Численный порт сверяется с Python-эталоном (golden.json, tolerance ≤1e-4).
 */
class LinearDetector(private val weights: LinearWeights) {

    fun score(features: FloatArray): Float {
        require(features.size == weights.nFeatures) {
            "длина features (${features.size}) != nFeatures (${weights.nFeatures})"
        }
        var acc = weights.bias.toDouble()
        val w = weights.weights
        for (i in features.indices) {
            acc += w[i].toDouble() * features[i].toDouble()
        }
        return sigmoid(acc).toFloat()
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
