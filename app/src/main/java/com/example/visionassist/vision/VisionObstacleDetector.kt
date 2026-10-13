package com.example.visionassist.vision

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

class VisionObstacleDetector(
    context: Context,
    private val onDanger: (score: Float) -> Unit
) : ImageAnalysis.Analyzer {

    // Adjustable “danger level”: bigger box => closer
    // Increase this to warn only when object is very close.
    var dangerBoxAreaRatio = 0.18f  // 18% of screen area

    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableMultipleObjects()
        .enableClassification()
        .build()

    private val detector = ObjectDetection.getClient(options)

    private var lastWarnMs = 0L

    fun analysisUseCase(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(java.util.concurrent.Executors.newSingleThreadExecutor(), this) }
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(image)
            .addOnSuccessListener { objects ->
                val w = imageProxy.width.toFloat().coerceAtLeast(1f)
                val h = imageProxy.height.toFloat().coerceAtLeast(1f)
                val screenArea = w * h

                var danger = false
                var bestRatio = 0f

                for (obj in objects) {
                    val box = obj.boundingBox
                    val area = box.width().toFloat() * box.height().toFloat()
                    val ratio = area / screenArea
                    if (ratio > bestRatio) bestRatio = ratio
                    if (ratio >= dangerBoxAreaRatio) {
                        danger = true
                        break
                    }
                }

                val now = System.currentTimeMillis()
                if (danger && now - lastWarnMs > 2000) {
                    lastWarnMs = now
                    onDanger(bestRatio)
                }
            }
            .addOnFailureListener {
                // ignore detection failure
            }
            .addOnCompleteListener {
                // ✅ ALWAYS close imageProxy to prevent CameraX crash/ANR
                imageProxy.close()
            }
    }
}
