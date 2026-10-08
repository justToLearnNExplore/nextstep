package com.northstar.nextstep.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import com.northstar.nextstep.agent.agent.AgentRunner
import com.northstar.nextstep.agent.exec.ActionExecutor
import com.northstar.nextstep.agent.overlay.BubbleOverlay
import com.northstar.nextstep.agent.overlay.OverlayHost
import com.northstar.nextstep.agent.screen.ScreenReader
import com.northstar.nextstep.agent.voice.VoiceIO
import org.json.JSONObject

/**
 * Hosts everything that must outlive the React Native screen: the floating bubble, the screen
 * reader, the action executor, voice, and the agent loop. Enabled by the user in
 * Settings → Accessibility → NextStep helper (onboarding walks them through it).
 */
class NextStepAccessibilityService : AccessibilityService(), OverlayHost {

  lateinit var prefs: Prefs; private set
  lateinit var overlay: BubbleOverlay; private set
  lateinit var reader: ScreenReader; private set
  lateinit var executor: ActionExecutor; private set
  lateinit var runner: AgentRunner; private set
  private lateinit var voice: VoiceIO

  override fun onServiceConnected() {
    super.onServiceConnected()
    prefs = Prefs(this)
    overlay = BubbleOverlay(this, prefs, this)
    reader = ScreenReader(this)
    executor = ActionExecutor(this)
    voice = VoiceIO(this)
    runner = AgentRunner(this, prefs, overlay, reader, executor, voice)
    instance = this
    if (prefs.overlayEnabled) overlay.show()
    AgentBus.emit("service", JSONObject().put("connected", true))
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // The agent pulls screen state on demand; when the user switches apps we fold the menu
    // back into the bubble, as the spec asks.
    if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
      event.packageName != null && event.packageName != packageName && !runner.isRunning) {
      overlay.closePopup()
    }
  }

  override fun onInterrupt() {}

  override fun onDestroy() {
    instance = null
    if (::overlay.isInitialized) overlay.remove()
    if (::voice.isInitialized) voice.shutdown()
    AgentBus.emit("service", JSONObject().put("connected", false))
    super.onDestroy()
  }

  // ---- OverlayHost -----------------------------------------------------------------------

  override fun onOpenApp(key: String) = runner.openQuickApp(key)

  override fun onUnderstandScreen() = runner.understandScreen()

  /** Opens the NextStep "Talk" screen (picture shortcuts + big mic) in the React Native app. */
  override fun onTalk() {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("nextstep://talk"))
      .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
  }

  /** "Close NextStep": stops any task, removes the bubble and turns the service off. */
  override fun onCloseNextStep() {
    if (runner.isRunning) runner.stop()
    overlay.remove()
    disableSelf()
  }

  override fun onStopNow() = runner.stop()

  companion object {
    @Volatile var instance: NextStepAccessibilityService? = null
      private set
  }
}
