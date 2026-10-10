package com.northstar.nextstep.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.bundleOf
import com.northstar.nextstep.agent.voice.VoiceIO
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONObject

private const val AGENT_EVENT = "onAgentEvent"

/** JS bridge: permissions/onboarding, configuration, and commands into the running agent. */
class NextStepAgentModule : Module() {

  private val context: Context
    get() = appContext.reactContext ?: throw CodedException("ERR_NO_CONTEXT", "React context unavailable", null)

  private val prefs get() = Prefs(context)
  private val service get() = NextStepAccessibilityService.instance
  private var voice: VoiceIO? = null

  private val busListener = AgentBus.Listener { type, payload ->
    sendEvent(AGENT_EVENT, bundleOf("type" to type, "payload" to payload.toString()))
  }

  override fun definition() = ModuleDefinition {
    Name("NextStepAgent")

    Events(AGENT_EVENT)
    OnStartObserving(AGENT_EVENT) { AgentBus.subscribe(busListener) }
    OnStopObserving(AGENT_EVENT) { AgentBus.unsubscribe(busListener) }
    OnDestroy { AgentBus.unsubscribe(busListener); voice?.shutdown() }

    // ---- permissions & onboarding ----

    Function("isAccessibilityEnabled") { service != null }

    Function("openAccessibilitySettings") {
      context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Function("isNotificationAccessEnabled") {
      NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    Function("openNotificationAccessSettings") {
      val cn = ComponentName(context, "com.northstar.nextstep.agent.notify.NotificationWatcher")
      val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }

    // ---- configuration (language pack, backend, consent flags) ----

    Function("configure") { cfg: Map<String, Any?> ->
      val p = prefs
      (cfg["apiBaseUrl"] as? String)?.let { p.apiBaseUrl = it }
      (cfg["language"] as? String)?.let { p.language = it }
      (cfg["authToken"] as? String)?.let { p.authToken = it }
      (cfg["refreshToken"] as? String)?.let { p.refreshToken = it }
      (cfg["tokenExpiresAt"] as? Number)?.let { p.tokenExpiresAt = it.toLong() }
      (cfg["firebaseApiKey"] as? String)?.let { p.firebaseApiKey = it }
      (cfg["deviceId"] as? String)?.let { p.deviceId = it }
      (cfg["scamCheckEnabled"] as? Boolean)?.let { p.scamCheckEnabled = it }
      (cfg["overlayEnabled"] as? Boolean)?.let { p.overlayEnabled = it }
      (cfg["labels"] as? Map<*, *>)?.let { p.labelsJson = JSONObject(it).toString() }
    }

    // ---- agent commands ----

    Function("startTask") { goal: String -> requireService().runner.startTask(goal); Unit }
    Function("stopTask") { service?.runner?.stop(); Unit }
    Function("understandScreen") { requireService().runner.understandScreen(); Unit }
    Function("showBubble") { prefs.overlayEnabled = true; requireService().overlay.show(); Unit }
    Function("hideBubble") { prefs.overlayEnabled = false; service?.overlay?.remove(); Unit }

    /** Medicine photo → WhatsApp with a confirmed Send. Falls back to a plain share if the service is off. */
    Function("shareImageToWhatsApp") { path: String, phone: String?, caption: String, recipient: String ->
      val svc = service
      if (svc != null) {
        svc.runner.shareImageToWhatsApp(path, phone, caption, recipient)
      } else {
        val uri = androidx.core.content.FileProvider.getUriForFile(
          context, "${context.packageName}.nextstep.files", java.io.File(path))
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
          type = "image/jpeg"
          putExtra(Intent.EXTRA_STREAM, uri)
          putExtra(Intent.EXTRA_TEXT, caption)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
      Unit
    }

    Function("isAppInstalled") { pkg: String ->
      runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    /** Sends NextStep to the background so the agent can work in the target app. */
    Function("minimizeApp") { appContext.currentActivity?.moveTaskToBack(true); Unit }

    /** Records and transcribes one utterance. Resolves {text, error}; see Heard for error codes. */
    AsyncFunction("listenOnce") { lang: String, promise: Promise ->
      val v = voice ?: VoiceIO(context).also { voice = it }
      v.listen(lang) { promise.resolve(it.toMap()) }
    }

    Function("cancelListening") { voice?.cancelListening(); Unit }
  }

  private fun requireService() = service
    ?: throw CodedException("ERR_SERVICE_OFF", "NextStep accessibility service is not enabled", null)
}
