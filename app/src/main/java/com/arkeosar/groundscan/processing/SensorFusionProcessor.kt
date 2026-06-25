package com.arkeosar.groundscan.processing

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stabilises the phone magnetometer for live ground scanning.
 *
 * The processor builds a rolling baseline during the first seconds of a scan,
 * subtracts local drift, suppresses small jitter, and amplifies meaningful
 * magnetic changes before they reach the grid/3D renderer.
 */
class SensorFusionProcessor(
    private val calibrationSamples: Int = 45,
    private val smoothingAlpha: Float = 0.18f,
    private val driftAlpha: Float = 0.006f,
    private val noiseFloor: Float = 0.65f,
    private val sensitivity: Float = 2.8f
) {
    data class Output(
        val processedValue: Float,
        val rawMagnitude: Float,
        val delta: Float,
        val baseline: Float,
        val anomalyScore: Float,
        val confidence: Float,
        val targetHint: String
    )

    private var sampleCount = 0
    private var baseline = 0f
    private var smoothed = 0f
    private var deltaMean = 0f
    private var deltaVar = 1f

    fun reset() {
        sampleCount = 0
        baseline = 0f
        smoothed = 0f
        deltaMean = 0f
        deltaVar = 1f
    }

    fun process(x: Float, y: Float, z: Float): Output {
        val magnitude = sqrt(x * x + y * y + z * z)
        sampleCount++

        if (sampleCount <= calibrationSamples) {
            baseline += (magnitude - baseline) / sampleCount
            smoothed = baseline
            return Output(0f, magnitude, 0f, baseline, 0f, sampleCount / calibrationSamples.toFloat(), "Kalibrasyon")
        }

        // Very slow baseline movement tracks environmental drift without
        // swallowing short anomaly spikes.
        baseline = baseline * (1f - driftAlpha) + magnitude * driftAlpha
        val rawDelta = magnitude - baseline
        val absDelta = abs(rawDelta)

        // Adaptive noise model. This makes the app calm in a magnetically noisy
        // room but still responsive outside when the background is stable.
        deltaMean = deltaMean * 0.98f + absDelta * 0.02f
        val err = absDelta - deltaMean
        deltaVar = deltaVar * 0.98f + err * err * 0.02f
        val adaptiveFloor = maxOf(noiseFloor, deltaMean + sqrt(deltaVar) * 0.55f)

        val cleaned = maxOf(0f, absDelta - adaptiveFloor)
        smoothed = smoothed * (1f - smoothingAlpha) + cleaned * smoothingAlpha
        val amplified = smoothed * sensitivity
        val anomalyScore = (amplified / 18f).coerceIn(0f, 1f)
        val confidence = when {
            sampleCount < calibrationSamples + 15 -> 0.45f
            adaptiveFloor > 8f -> 0.55f
            else -> (0.72f + anomalyScore * 0.24f).coerceIn(0f, 0.96f)
        }
        val hint = when {
            anomalyScore >= 0.78f && rawDelta > 0f -> "Güçlü metal/anomali"
            anomalyScore >= 0.78f && rawDelta < 0f -> "Güçlü boşluk/kontrast"
            anomalyScore >= 0.45f && rawDelta > 0f -> "Metal olasılığı"
            anomalyScore >= 0.45f && rawDelta < 0f -> "Boşluk/zemin farkı"
            anomalyScore >= 0.18f -> "Zayıf anomali"
            else -> "Normal zemin"
        }

        return Output(amplified, magnitude, rawDelta, baseline, anomalyScore, confidence, hint)
    }
}
