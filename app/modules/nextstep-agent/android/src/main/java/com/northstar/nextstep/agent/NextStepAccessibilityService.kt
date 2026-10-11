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
    // Connected while NextStep itself is open (e.g. right after an update): keep the bubble out of it.
    if (rootInActiveWindow?.packageName == packageName) overlay.setBubbleHidden(true)
    AgentBus.emit("service", JSONObject().put("connected", true))
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // The agent pulls screen state on demand; when the user switches apps we fold the menu
    // back into the bubble, as the spec asks.
    if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.packageName == null) return
    val inOurApp = event.packageName == packageName && event.className?.toString()?.endsWith("MainActivity") == true
    if (inOurApp) {
      // Opening NextStep always brings the bubble back if the user had hidden it.
      if (!prefs.overlayEnabled) { prefs.overlayEnabled = true; overlay.show() }
      // Inside NextStep itself the bubble and its menu aren't needed (they'd cover the screen).
      overlay.setBubbleHidden(true)
      overlay.closePopup()
    } else if (event.packageName != packageName) {
      overlay.setBubbleHidden(false)
      if (!runner.isRunning) overlay.closePopup()
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

  override fun onUnderstandScreen() = runner.understandScreen()

  override fun onCheckScam() = runner.checkScreenForScam()

  override fun onTellMe() = runner.tellMe()

  override fun onOpenNextStep() {
    packageManager.getLaunchIntentForPackage(packageName)
      ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      ?.let(::startActivity)
  }

  /**
   * "Hide": removes only the bubble. The helper stays on, so opening the NextStep app brings the
   * bubble straight back (the old Close turned the whole service off, which only Android Settings
   * could undo).
   */
  override fun onHide() {
    if (runner.isRunning) runner.stop()
    prefs.overlayEnabled = false
    overlay.remove()
  }

  override fun onStopNow() = runner.stop()

  companion object {
    @Volatile var instance: NextStepAccessibilityService? = null
      private set
  }
}
