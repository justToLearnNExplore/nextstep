package com.northstar.nextstep.agent.medicine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.Promise
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * In-app camera for medicine labels: back camera preview, live framing guidance
 * (more light / hold steady / move closer / move back / ready) and a capture that also returns
 * the recognised label text.
 */
class MedicineCameraView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

  private val onGuidance by EventDispatcher()
  private val onCameraError by EventDispatcher()

  private val preview = PreviewView(context).apply {
    scaleType = PreviewView.ScaleType.FILL_CENTER
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
  }
  private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val analyzer = LabelAnalyzer { state, m, text ->
    Log.d(TAG, "guidance=$state brightness=${m.brightness.toInt()} sharp=${m.sharpness.toInt()} motion=${m.motion.toInt()} chars=${m.textChars} line=${"%.3f".format(m.lineHeight)}")
    post {
      onGuidance(mapOf(
        "state" to state,
        "text" to text,
        "brightness" to m.brightness,
        "sharpness" to m.sharpness,
        "motion" to m.motion,
        "lineHeight" to m.lineHeight,
      ))
    }
  }
  private var capture: ImageCapture? = null
  private var camera: Camera? = null
  private var provider: ProcessCameraProvider? = null

  init {
    addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    bind()
  }

  override fun onDetachedFromWindow() {
    release()
    super.onDetachedFromWindow()
  }

  private fun bind() {
    val owner = appContext.currentActivity as? LifecycleOwner ?: run {
      Log.e(TAG, "camera bind: activity is not a LifecycleOwner")
      onCameraError(mapOf("message" to "no_lifecycle_owner")); return
    }
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
      runCatching {
        val p = future.get()
        val selector = ResolutionSelector.Builder()
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
        val pv = Preview.Builder().setResolutionSelector(selector).build()
          .also { it.surfaceProvider = preview.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
          .setResolutionSelector(selector)
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .build().also { it.setAnalyzer(analysisExecutor, analyzer) }
        val cap = ImageCapture.Builder()
          .setResolutionSelector(selector)
          .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
          .build()
        p.unbindAll()
        camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis, cap)
        capture = cap
        provider = p
        Log.i(TAG, "camera bound (back camera)")
      }.onFailure {
        Log.e(TAG, "camera bind failed", it)
        onCameraError(mapOf("message" to (it.message ?: "bind_failed")))
      }
    }, ContextCompat.getMainExecutor(context))
  }

  fun release() {
    runCatching { provider?.unbindAll() }
    provider = null; capture = null; camera = null
  }

  fun setTorch(on: Boolean) {
    camera?.cameraControl?.enableTorch(on)
  }

  /** Captures a full-resolution JPEG into cache/medicine and OCRs it. */
  fun takePhoto(promise: Promise) {
    val cap = capture ?: return promise.reject("ERR_CAMERA", "Camera not ready", null)
    val dir = File(context.cacheDir, "medicine").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000 }?.forEach { it.delete() }
    val file = File(dir, "medicine-${System.currentTimeMillis()}.jpg")
    cap.takePicture(
      ImageCapture.OutputFileOptions.Builder(file).build(),
      ContextCompat.getMainExecutor(context),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(out: ImageCapture.OutputFileResults) {
          val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
          recognizer.process(InputImage.fromFilePath(context, Uri.fromFile(file)))
            .addOnCompleteListener { task ->
              recognizer.close()
              promise.resolve(mapOf(
                "path" to file.absolutePath,
                "uri" to Uri.fromFile(file).toString(),
                "text" to (task.result?.text ?: ""),
              ))
            }
        }
        override fun onError(e: ImageCaptureException) {
          promise.reject("ERR_CAPTURE", e.message ?: "capture_failed", e)
        }
      })
  }

  companion object { private const val TAG = "NextStepCamera" }

  fun destroy() {
    release()
    analyzer.close()
    analysisExecutor.shutdown()
  }
}
