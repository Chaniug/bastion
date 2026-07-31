package com.bastion.app.ui.scanner

import com.bastion.app.logging.runCatchingObserved
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("UnsafeOptInUsageError")
internal class QrCameraScanSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    zxingFormats: Collection<BarcodeFormat>,
    private val generation: Int,
    private val diagnostics: QrScannerDiagnostics?,
    private val onCandidates: (List<String>, barcodeCount: Int, durationMs: Long) -> Boolean,
    private val onRestartRequested: (QrScanRestartReason) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val controller = LifecycleCameraController(appContext)
    private val zxingFormats: Collection<BarcodeFormat> = zxingFormats
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val healthPolicy = QrScanHealthPolicy()
    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val processingFrame = AtomicBoolean(false)
    private val previewStreaming = AtomicBoolean(false)
    private val previewObserver = Observer<PreviewView.StreamState> { state ->
        val streaming = state == PreviewView.StreamState.STREAMING
        val changed = previewStreaming.getAndSet(streaming) != streaming
        if (changed) diagnostics?.logPreviewState(streaming)
        if (streaming) {
            requestCenterFocus(reason = "preview_streaming")
        }
    }

    fun start() {
        check(active.compareAndSet(false, true)) { "QR camera session already started" }
        val startedAt = SystemClock.elapsedRealtime()
        healthPolicy.onSessionStarted(startedAt)
        diagnostics?.logSessionStarted(generation)
        diagnostics?.logCameraProviderRequested(1)

        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )
        controller.setTapToFocusEnabled(true)
        controller.setImageAnalysisAnalyzer(analysisExecutor, ::analyzeFrame)

        previewView.controller = controller
        previewView.previewStreamState.observeForever(previewObserver)

        runCatchingObserved {
            controller.bindToLifecycle(lifecycleOwner)
        }.onFailure { error ->
            diagnostics?.logCameraBindFailed(error)
            requestRestart(QrScanRestartReason.FrameStreamStopped)
            return
        }

        controller.initializationFuture.addListener(
            {
                if (!active.get()) return@addListener
                runCatchingObserved { controller.initializationFuture.get() }
                    .onSuccess {
                        diagnostics?.logCameraBindSuccess(SystemClock.elapsedRealtime() - startedAt)
                        previewView.post { requestCenterFocus(reason = "session_start") }
                    }
                    .onFailure { error ->
                        diagnostics?.logCameraProviderFailed(error)
                        requestRestart(QrScanRestartReason.FrameStreamStopped)
                    }
            },
            mainExecutor
        )
    }

    fun tick(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!active.get()) return
        when (val action = healthPolicy.nextAction(nowMs, previewStreaming.get())) {
            QrScanHealthAction.None -> Unit
            QrScanHealthAction.Refocus -> {
                if (requestCenterFocus(reason = "periodic")) {
                    healthPolicy.onRefocusRequested(nowMs)
                }
            }
            is QrScanHealthAction.Restart -> requestRestart(action.reason)
        }
    }

    fun isProcessingFrame(): Boolean = processingFrame.get()

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!active.get()) {
            imageProxy.close()
            return
        }
        if (!processingFrame.compareAndSet(false, true)) {
            diagnostics?.logFrameSkipped()
            imageProxy.close()
            return
        }

        val frameStartedAt = SystemClock.elapsedRealtime()
        healthPolicy.onFrameStarted(frameStartedAt)
        diagnostics?.logFrameStarted(imageProxy.imageInfo.rotationDegrees)
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            diagnostics?.logFrameMissingImage()
            healthPolicy.onFrameCompleted(SystemClock.elapsedRealtime(), succeeded = false)
            processingFrame.set(false)
            imageProxy.close()
            return
        }

        val frameFinished = AtomicBoolean(false)
        fun finishFrame(succeeded: Boolean) {
            if (!frameFinished.compareAndSet(false, true)) return
            healthPolicy.onFrameCompleted(SystemClock.elapsedRealtime(), succeeded)
            processingFrame.set(false)
            runCatchingObserved { imageProxy.close() }
        }

        val candidates = decodeFrameZxing(imageProxy, zxingFormats)
        val durationMs = SystemClock.elapsedRealtime() - frameStartedAt
        val matched = runCatchingObserved {
            onCandidates(candidates, if (candidates.isNotEmpty()) 1 else 0, durationMs)
        }.getOrDefault(false)
        diagnostics?.logFrameSuccess(
            durationMs = durationMs,
            barcodeCount = if (candidates.isNotEmpty()) 1 else 0,
            candidateCount = candidates.size,
            matched = matched
        )
        finishFrame(succeeded = candidates.isNotEmpty())
    }

    private fun requestCenterFocus(reason: String): Boolean {
        if (!active.get() || !previewStreaming.get()) return false
        if (previewView.width <= 0 || previewView.height <= 0) return false

        return runCatchingObserved {
            val point = previewView.meteringPointFactory.createPoint(
                previewView.width / 2f,
                previewView.height / 2f,
                FOCUS_POINT_SIZE
            )
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or
                    FocusMeteringAction.FLAG_AE or
                    FocusMeteringAction.FLAG_AWB
            )
                .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
                .build()
            val cameraControl = controller.cameraControl ?: return@runCatchingObserved false
            cameraControl.startFocusAndMetering(action)
            diagnostics?.logRefocusRequested(reason)
            true
        }.onFailure { error ->
            diagnostics?.logRefocusFailed(error)
        }.getOrDefault(false)
    }

    private fun requestRestart(reason: QrScanRestartReason) {
        if (!active.compareAndSet(true, false)) return
        diagnostics?.logSessionRestartRequested(reason)
        closeResources()
        mainExecutor.execute { onRestartRequested(reason) }
    }

    override fun close() {
        active.set(false)
        closeResources()
    }

    private fun closeResources() {
        if (!closed.compareAndSet(false, true)) return
        diagnostics?.logDispose(processingFrame.get())
        previewStreaming.set(false)
        runCatchingObserved { previewView.previewStreamState.removeObserver(previewObserver) }
        runCatchingObserved { controller.clearImageAnalysisAnalyzer() }
        runCatchingObserved { previewView.controller = null }
        runCatchingObserved { controller.unbind() }
        analysisExecutor.shutdown()
    }

    private companion object {
        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 960
        private const val FOCUS_POINT_SIZE = 0.24f
        private const val FOCUS_AUTO_CANCEL_SECONDS = 3L
    }
}

private fun decodeFrameZxing(imageProxy: ImageProxy, formats: Collection<BarcodeFormat>): List<String> {
    val bitmap = runCatchingObserved { imageProxyToBitmap(imageProxy) }.getOrNull() ?: return emptyList()
    return try {
        decodeBitmapZxing(bitmap, formats)
    } finally {
        bitmap.recycle()
    }
}

/**
 * 将 ImageAnalysis 产出的 YUV_420_888 [ImageProxy] 转为可解码的 [Bitmap]。
 * camera-core 1.5.x 未提供 ImageProxy.toBitmap() 扩展，这里手动完成
 * YUV -> NV21 -> JPEG -> Bitmap 的转换，并按传感器旋转角校正方向。
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    val mediaImage = imageProxy.image ?: return null
    val width = mediaImage.width
    val height = mediaImage.height
    val nv21 = yuv420ToNv21(mediaImage)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val jpeg = out.toByteArray()
    val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
    val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
    if (rotation == 0f) return raw
    val matrix = Matrix().apply { postRotate(rotation) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    raw.recycle()
    return rotated
}

/**
 * 把 YUV_420_888 的三平面数据紧凑为 NV21（Y 平面 + 交错 VU），
 * 兼容不同设备的 rowStride / pixelStride 布局。
 */
private fun yuv420ToNv21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val planes = image.planes
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride

    val nv21 = ByteArray(width * height + (width * height) / 2)
    var pos = 0
    val yRow = ByteArray(yRowStride)
    for (row in 0 until height) {
        yBuffer.position(row * yRowStride)
        yBuffer.get(yRow, 0, yRowStride)
        for (col in 0 until width) {
            nv21[pos++] = yRow[col * yPixelStride]
        }
    }
    val uvHeight = height / 2
    val uvWidth = width / 2
    val uRowArr = ByteArray(uRowStride)
    val vRowArr = ByteArray(vRowStride)
    for (row in 0 until uvHeight) {
        uBuffer.position(row * uRowStride)
        uBuffer.get(uRowArr, 0, uRowStride)
        vBuffer.position(row * vRowStride)
        vBuffer.get(vRowArr, 0, vRowStride)
        for (col in 0 until uvWidth) {
            nv21[pos++] = vRowArr[col * vPixelStride]
            nv21[pos++] = uRowArr[col * uPixelStride]
        }
    }
    return nv21
}

internal fun buildZxingHints(formats: Collection<BarcodeFormat>): Map<DecodeHintType, Any> {
    val hints = HashMap<DecodeHintType, Any>()
    hints[DecodeHintType.TRY_HARDER] = true
    hints[DecodeHintType.POSSIBLE_FORMATS] = formats.toList()
    return hints
}

internal fun buildCandidates(result: Result): List<String> {
    val text = result.text?.trim().takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(text)
}

internal fun decodeBitmapZxing(bitmap: android.graphics.Bitmap, formats: Collection<BarcodeFormat>): List<String> {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val source = RGBLuminanceSource(w, h, pixels)
    val binary = BinaryBitmap(HybridBinarizer(source))
    val result = runCatchingObserved {
        MultiFormatReader().decode(binary, buildZxingHints(formats))
    }.getOrNull() ?: return emptyList()
    return buildCandidates(result)
}
