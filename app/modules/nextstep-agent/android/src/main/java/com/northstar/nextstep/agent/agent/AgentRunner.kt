package com.northstar.nextstep.agent.agent

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.northstar.nextstep.agent.AgentBus
import com.northstar.nextstep.agent.Prefs
import com.northstar.nextstep.agent.exec.ActionExecutor
import com.northstar.nextstep.agent.overlay.BubbleOverlay
import com.northstar.nextstep.agent.overlay.PanelButton
import com.northstar.nextstep.agent.overlay.Style
import com.northstar.nextstep.agent.safety.Gate
import com.northstar.nextstep.agent.safety.SafetyGate
import com.northstar.nextstep.agent.safety.Verdict
import com.northstar.nextstep.agent.safety.maxOf
import com.northstar.nextstep.agent.screen.ScreenReader
import com.northstar.nextstep.agent.voice.VoiceIO
import android.accessibilityservice.AccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The on-device half of the agent loop:
 *
 *   goal ─► backend plans ─► user approves once ─► loop {
 *       screen (tree + screenshot) ─► backend (Gemini Computer Use + Guardian) ─► actions
 *       each action: server gate ⊕ local SafetyGate ─► AUTO run | CONFIRM ask | PRIVATE hand over | BLOCKED
 *   } ─► done
 *
 * All network and gesture work happens on one worker thread; UI goes through the overlay.
 */
class AgentRunner(
  private val service: AccessibilityService,
  private val prefs: Prefs,
  private val overlay: BubbleOverlay,
  private val reader: ScreenReader,
  private val executor: ActionExecutor,
  private val voice: VoiceIO,
) {
  private val worker = Executors.newSingleThreadExecutor()
  private val api = BackendClient(prefs)
  private val stopped = AtomicBoolean(false)
  @Volatile private var taskId: String? = null
  @Volatile private var pendingDecision: CompletableFuture<Boolean>? = null

  val isRunning get() = taskId != null

  private fun t(key: String, fallback: String) = prefs.label(key, fallback)
  private val lang get() = prefs.language

  // ---- task lifecycle --------------------------------------------------------------------

  fun startTask(goal: String) = worker.execute {
    if (isRunning) return@execute
    stopped.set(false)
    overlay.setBusy(true)
    try {
      overlay.showPanel(t("thinking", "Let me think about that…"), emptyList())
      val screen = overlay.whileHidden { reader.capture(withScreenshot = true) }
      val res = api.post("/v1/tasks", JSONObject()
        .put("goal", goal).put("language", lang).put("screen", screen.toJson()))
      val id = res.getString("task_id")
      taskId = id
      log("plan", res)

      val plan = res.getJSONObject("plan")
      if (plan.optBoolean("refused")) {
        say(plan.optString("summary"), finalMessage = true); return@execute
      }
      val steps = plan.optJSONArray("steps").strings()
      val approved = ask(plan.getString("summary"), t("yesDoIt", "Yes, do it"), t("no", "No"), steps)
      api.post("/v1/tasks/$id/consent", JSONObject().put("approved", approved))
      if (!approved) { say(t("okCancelled", "Okay, I won't do it."), finalMessage = true); return@execute }

      loop(id)
    } catch (e: Exception) {
      log("error", JSONObject().put("message", e.message))
      say(t("somethingWrong", "Sorry, something went wrong. I have stopped."), finalMessage = true)
    } finally {
      taskId = null
      overlay.setBusy(false)
    }
  }

  private fun loop(id: String) {
    var results = JSONArray()
    repeat(MAX_TURNS) {
      if (stopped.get()) return
      val screen = overlay.whileHidden { reader.capture(withScreenshot = true) }
      val res = api.post("/v1/tasks/$id/step", JSONObject()
        .put("screen", screen.toJson()).put("results", results))
      log("step", res)
      res.optString("status_text").takeIf { it.isNotBlank() }?.let {
        overlay.showPanel(it, emptyList(), steps = res.optJSONArray("done_steps").strings())
      }
      if (res.optBoolean("done")) {
        say(res.optString("message", t("allDone", "All done.")), finalMessage = true)
        return
      }
      results = JSONArray()
      val actions = res.optJSONArray("actions") ?: JSONArray()
      for (i in 0 until actions.length()) {
        if (stopped.get()) return
        val a = actions.getJSONObject(i)
        val result = runGated(a, screen.packageName, screen.nodes, screen.width, screen.height)
        results.put(JSONObject().put("call_id", a.optString("call_id")).put("name", a.getString("name"))
          .put("result", result))
        if (result.optString("error") == "user_declined" && a.optString("gate") != "auto") break
      }
    }
    say(t("tooLong", "This is taking too long, so I stopped. Nothing was ordered or sent."), finalMessage = true)
  }

  /** Server gate first, then the on-device SafetyGate may only raise it. */
  private fun runGated(a: JSONObject, pkg: String, nodes: JSONArray, w: Int, h: Int): JSONObject {
    val name = a.getString("name")
    val args = a.optJSONObject("args") ?: JSONObject()
    val server = Verdict(Gate.parse(a.optString("gate", "confirm")), a.optString("reason"))
    val verdict = maxOf(server, SafetyGate.check(name, args, pkg, nodes, w, h))
    val prompt = a.optString("ask").ifBlank { a.optString("intent") }
    log("action", JSONObject(a.toString()).put("final_gate", verdict.gate.name).put("local_reason", verdict.reason))

    return when (verdict.gate) {
      Gate.AUTO -> overlay.whileHidden { executor.execute(name, args, w, h) }
      Gate.CONFIRM -> {
        val yes = ask(prompt.ifBlank { t("confirmGeneric", "Shall I do this?") },
          a.optString("yes_label").ifBlank { t("yes", "Yes") }, t("no", "No"))
        if (!yes) JSONObject().put("ok", false).put("error", "user_declined")
        else overlay.whileHidden { executor.execute(name, args, w, h) }.put("safety_acknowledgement", true)
      }
      Gate.PRIVATE -> {
        // Hand over: the user types their PIN/OTP/password themselves. We never see or type it.
        val done = ask(a.optString("ask").ifBlank {
          t("privateStep", "This step is private. Please do it yourself, then tap Continue.")
        }, t("continue", "Continue"), t("stopNow", "Stop now"), compact = true)
        if (!done) { stop(); JSONObject().put("ok", false).put("error", "user_stopped") }
        else JSONObject().put("ok", true).put("handed_to_user", true)
      }
      Gate.BLOCKED -> {
        say(a.optString("ask").ifBlank { t("blocked", "I can't do that, it isn't safe.") })
        JSONObject().put("ok", false).put("error", "blocked_by_safety")
      }
    }
  }

  fun stop() {
    stopped.set(true)
    pendingDecision?.complete(false)
    voice.stopSpeaking(); voice.cancelListening()
    val id = taskId
    worker.execute { runCatching { if (id != null) api.post("/v1/tasks/$id/stop", JSONObject()) } }
    overlay.showPanel(t("stopped", "Stopped. Nothing more will happen."),
      listOf(PanelButton(t("ok", "OK"), Style.PRIMARY) { overlay.hidePanel() }))
    voice.speak(t("stopped", "Stopped. Nothing more will happen."), lang)
  }

  // ---- other overlay features ------------------------------------------------------------

  fun understandScreen() = worker.execute {
    try {
      overlay.showPanel(t("looking", "Let me look at this screen…"), emptyList())
      val screen = overlay.whileHidden { reader.capture(withScreenshot = true) }
      val res = api.post("/v1/screen/explain", JSONObject().put("language", lang).put("screen", screen.toJson()))
      log("explain", res)
      val buttons = res.optJSONArray("options").objects().map { o ->
        val id = o.getString("id")
        PanelButton(o.getString("label"), if (o.optBoolean("recommended")) Style.GO else Style.OUTLINE) {
          overlay.hidePanel()
          worker.execute {
            when (id) {
              "back", "close" -> executor.execute("go_back", JSONObject(), 0, 0)
              "home" -> executor.execute("go_home", JSONObject(), 0, 0)
              else -> Unit
            }
          }
        }
      }
      val tone = if (res.optString("risk") == "danger") Style.STOP else null
      overlay.showPanel(res.getString("explanation"), buttons, tone)
      voice.speak(res.getString("explanation"), lang)
    } catch (e: Exception) {
      say(t("somethingWrong", "Sorry, something went wrong."), finalMessage = true)
    }
  }

  /** Scam shield: called by the NotificationWatcher for opted-in message apps. Never taps links. */
  fun checkMessage(sourcePkg: String, sender: String, text: String) = worker.execute {
    runCatching {
      val res = api.post("/v1/scam/check", JSONObject().put("language", lang)
        .put("source", sourcePkg).put("sender", sender).put("text", text))
      log("scam_check", res)
      if (res.optString("risk") !in setOf("high", "medium")) return@runCatching
      val buttons = mutableListOf<PanelButton>()
      res.optString("official_url").takeIf { it.startsWith("https://") }?.let { url ->
        buttons += PanelButton(res.optString("official_label", t("openOfficial", "Open official website")), Style.GO) {
          overlay.hidePanel()
          service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
      buttons += PanelButton(t("ok", "OK"), Style.PRIMARY) { overlay.hidePanel() }
      overlay.showPanel(res.getString("warning"), buttons, Style.STOP)
      voice.speak(res.getString("warning"), lang)
    }
  }

  fun openQuickApp(key: String) = worker.execute {
    val intent = when (key) {
      "phone" -> Intent(Intent.ACTION_DIAL)
      "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
      "whatsapp" -> service.packageManager.getLaunchIntentForPackage("com.whatsapp")
      "youtube" -> service.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
      else -> null
    }
    if (intent == null) { say(t("appMissing", "That app is not installed.")); return@execute }
    service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    log("open_app", JSONObject().put("app", key))
  }

  // ---- user dialogue ---------------------------------------------------------------------

  /**
   * Shows a yes/no panel, speaks it, and listens for a spoken answer at the same time.
   * Whichever comes first (tap or voice) wins. Stop always resolves to "no".
   */
  private fun ask(
    message: String, yesLabel: String, noLabel: String,
    steps: List<String> = emptyList(), compact: Boolean = false,
  ): Boolean {
    val decision = CompletableFuture<Boolean>()
    pendingDecision = decision
    overlay.showPanel(message, listOf(
      PanelButton(yesLabel, if (compact) Style.PRIMARY else Style.GO) { decision.complete(true) },
      PanelButton(noLabel, if (compact) Style.STOP else Style.OUTLINE) { decision.complete(false) },
    ), steps = steps)
    voice.speakAndWait((listOf(message) + steps).joinToString(". "), lang)
    if (!compact && !decision.isDone) {
      voice.listen(lang) { heard -> heard?.let { yesNo(it) }?.let { decision.complete(it) } }
    }
    val answer = runCatching { decision.get() }.getOrDefault(false)
    voice.cancelListening()
    pendingDecision = null
    overlay.hidePanel()
    log("decision", JSONObject().put("message", message).put("approved", answer))
    return answer && !stopped.get()
  }

  private fun yesNo(heard: String): Boolean? {
    val h = heard.lowercase()
    val yes = prefs.words("yesWords", listOf("yes", "ok", "okay", "sure", "do it", "haan", "ha", "हाँ", "हां", "ಹೌದು", "ಸರಿ"))
    val no = prefs.words("noWords", listOf("no", "stop", "don't", "cancel", "nahin", "नहीं", "ಬೇಡ", "ಇಲ್ಲ"))
    return when {
      no.any { h.contains(it) } -> false // "no" wins on ambiguity
      yes.any { h.contains(it) } -> true
      else -> null
    }
  }

  private fun say(message: String, finalMessage: Boolean = false) {
    if (message.isBlank()) return
    overlay.showPanel(message,
      if (finalMessage) listOf(PanelButton(t("ok", "OK"), Style.PRIMARY) { overlay.hidePanel() }) else emptyList())
    voice.speakAndWait(message, lang)
  }

  private fun log(kind: String, payload: JSONObject) =
    AgentBus.emit("agent", JSONObject().put("kind", kind).put("task_id", taskId).put("data", payload))

  private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
  private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

  companion object { const val MAX_TURNS = 40 }
}
