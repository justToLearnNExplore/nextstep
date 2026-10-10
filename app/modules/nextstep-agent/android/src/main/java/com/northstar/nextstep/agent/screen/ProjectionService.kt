package com.northstar.nextstep.agent.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import com.northstar.nextstep.agent.R

/**
 * Screen capture for Android 10 (API 29), which lacks AccessibilityService.takeScreenshot.
 * The user approves once per session ("Start now"); Android requires a foreground service of type
 * mediaProjection, shown as a small "NextStep is helping" notification. On Android 11+ this
 * service is never started.
 */
class ProjectionService : Service() {

  private var projection: MediaProjection? = null
  private var display: VirtualDisplay? = null
  private var reader: ImageReader? = null
  private var width = 0
  private var height = 0

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startAsForeground()
    val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
    @Suppress("DEPRECATION")
    val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
    if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

    val mpm = getSystemService(MediaProjectionManager::class.java)
    projection = mpm.getMediaProjection(code, data)?.also { p ->
      p.registerCallback(object : MediaProjection.Callback() {
        override fun onStop() { release(); stopSelf() }
      }, null)
    }
    @Suppress("DEPRECATION")
    val metrics = android.util.DisplayMetrics().also {
      getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
    }
    // Capture at half resolution: plenty for the model, cheaper to encode.
    width = metrics.widthPixels / 2
    height = metrics.heightPixels / 2
    reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    display = projection?.createVirtualDisplay("nextstep", width, height, metrics.densityDpi / 2,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    instance = this
    Log.i(TAG, "screen capture ready ${width}x$height")
    return START_NOT_STICKY
  }

  /** Latest frame as a bitmap, or null if capture isn't running yet. */
  fun capture(): Bitmap? {
    val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return null
    return image.use {
      val plane = it.planes[0]
      val rowPadding = plane.rowStride - plane.pixelStride * width
      val padded = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
      padded.copyPixelsFromBuffer(plane.buffer)
      Bitmap.createBitmap(padded, 0, 0, width, height).also { if (it != padded) padded.recycle() }
    }
  }

  private fun startAsForeground() {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "NextStep helper", NotificationManager.IMPORTANCE_LOW))
    val n = Notification.Builder(this, CHANNEL)
      .setContentTitle("NextStep is helping")
      .setContentText("NextStep can see the screen to help you. Stop anytime from the NextStep button.")
      .setSmallIcon(R.drawable.ic_nextstep_logo)
      .setOngoing(true)
      .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    } else {
      startForeground(NOTIFICATION_ID, n)
    }
  }

  private fun release() {
    display?.release(); display = null
    reader?.close(); reader = null
    projection?.stop(); projection = null
    if (instance === this) instance = null
  }

  override fun onDestroy() {
    release()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "NextStepScreen"
    private const val CHANNEL = "nextstep_capture"
    private const val NOTIFICATION_ID = 4101
    const val EXTRA_CODE = "code"
    const val EXTRA_DATA = "data"
    const val REQUEST_CODE = 4102

    @Volatile var instance: ProjectionService? = null
      private set

    /** Android 10 needs MediaProjection; Android 11+ uses the accessibility screenshot. */
    val needed get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    fun start(context: Context, resultCode: Int, data: Intent) {
      context.startForegroundService(Intent(context, ProjectionService::class.java)
        .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
    }
  }
}
