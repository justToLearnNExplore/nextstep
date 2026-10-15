package com.northstar.nextstep.agent.agent

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import java.io.File
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
import android.util.Log
import java.io.IOException
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
  /** Set when the user wants to say something else; holds what they said, if already heard. */
  @Volatile private var changeRequested = false
  @Volatile private var heardChange: String? = null

  val isRunning get() = taskId != null

  private fun t(key: String, fallback: String) = prefs.label(key, fallback)
  private val lang get() = prefs.language

  // ---- task lifecycle --------------------------------------------------------------------

  fun startTask(goal: String) = worker.execute {
    if (isRunning) return@execute
    stopped.set(false)
    overlay.setBusy(true)
    try {
      // Show what we heard right away, so the wait for the plan never feels like nothing happened.
      overlay.showPanel("“$goal”\n\n" + t("thinking", "Let me think about that…"), emptyList())
      val screen = overlay.whileHidden { reader.capture(withScreenshot = true) }
      val res = api.post("/v1/tasks", JSONObject()
        .put("goal", goal).put("language", lang).put("screen", screen.toJson()), timeoutMs = SLOW_CALL_MS)
      val id = res.getString("task_id")
      taskId = id
      log("plan", res)

      val plan = res.getJSONObject("plan")
      if (plan.optBoolean("refused")) {
        say(plan.optString("summary"), finalMessage = true); return@execute
      }
      // Photos use NextStep's own camera (no app-hopping); the user picks the chat and sends there.
      val flow = plan.optString("special_flow")
      if (flow == "medicine_photo" || flow == "photo_share") {
        overlay.hidePanel()
        val to = plan.optString("recipient").ifBlank { "doctor" }
        val link = if (flow == "photo_share") "nextstep://medicine?mode=photo" else "nextstep://medicine?to=$to"
        service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))
          .setPackage(service.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        return@execute
      }
      val steps = plan.optJSONArray("steps").strings()
      val summary = plan.getString("summary").let {
        if (res.optBoolean("uses_saved_routine")) "${t("savedRoutine", "I've done this before, so it will be quick.")} $it" else it
      }
      var approved = ask(summary, t("yesDoIt", "Yes, do it"), t("no", "No"), steps)
      // "Say something else" at the plan: take the change, then ask again (never cancel).
      while (!approved && changeRequested && !stopped.get()) {
        val said = takeChange(id, announce = false)
        approved = if (said != null) {
          ask(t("changeConfirm", "Got it: “{text}”. Shall I go ahead?").replace("{text}", said), t("yesDoIt", "Yes, do it"), t("no", "No"))
        } else {
          ask(summary, t("yesDoIt", "Yes, do it"), t("no", "No"), steps)  // didn't catch it: ask again
        }
      }
      api.post("/v1/tasks/$id/consent", JSONObject().put("approved", approved))
      if (!approved) { say(t("okCancelled", "Okay, I won't do it."), finalMessage = true); return@execute }

      loop(id)
    } catch (e: Exception) {
      Log.e(TAG, "task failed: $goal", e)
      log("error", JSONObject().put("message", e.message))
      say(explain(e), finalMessage = true)
    } finally {
      taskId = null
      overlay.setBusy(false)
    }
  }

  /** Turns a failure into a calm sentence the user can act on (never "error 503"). */
  private fun explain(e: Exception): String = when {
    e is BackendError && e.code == 503 && e.message?.contains("ai_quota") == true ->
      t("aiQuota", "My thinking service has reached today's limit. Please try again later. Nothing was ordered or sent.")
    e is BackendError && e.code == 503 ->
      t("aiBusy", "My thinking service is busy right now. Please try again in a minute. Nothing was ordered or sent.")
    e is IOException ->
      t("networkError", "I couldn't reach the internet. Please check it and try again.")
    else -> t("somethingWrong", "Sorry, something went wrong. I have stopped.")
  }

  private fun loop(id: String) {
    var results = JSONArray()
    repeat(MAX_TURNS) {
      if (stopped.get()) return
      if (changeRequested) takeChange(id)
      // Element list first: most steps (saved routines, rules, clear screens) don't need the
      // picture, and capturing + uploading it is the slowest part of a step on older phones.
      var screen = overlay.whileHidden { reader.capture(withScreenshot = false) }
      var res = api.post("/v1/tasks/$id/step", JSONObject()
        .put("screen", screen.toJson()).put("results", results), timeoutMs = SLOW_CALL_MS)
      if (res.optBoolean("need_screenshot")) {
        screen = overlay.whileHidden { reader.capture(withScreenshot = true) }
        res = api.post("/v1/tasks/$id/step", JSONObject()
          .put("screen", screen.toJson().put("screenshot_tried", true)).put("results", JSONArray()), timeoutMs = SLOW_CALL_MS)
      }
      log("step", res)
      res.optString("status_text").takeIf { it.isNotBlank() }?.let {
        overlay.showPanel(it, listOf(changeButton { changeRequested = true }), steps = res.optJSONArray("done_steps").strings())
        // Only key moments are said aloud (item added, fare found), as they're shown; routine
        // taps and scrolls stay silent. Questions that follow (confirm/hand-over) speak for themselves.
        val first = res.optJSONArray("actions")?.optJSONObject(0)
        if (first?.optBoolean("announce") == true && first.optString("gate", "auto") == "auto") voice.speak(it, lang)
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
        if (result.optString("error") in setOf("user_declined", "user_changed_plan") && a.optString("gate") != "auto") break
        if (changeRequested) break
        if (i < actions.length() - 1) Thread.sleep(BATCH_GAP_MS) // let the app update between batched taps
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

    if (verdict.gate == Gate.AUTO && name == "wait_for_app") {
      // The user is on the Play Store page: keep the bubble out of the way, but tell them.
      val msg = prompt.ifBlank { t("waitingInstall", "Please tap Install. I'll continue as soon as it's installed.") }
      overlay.showPanel(msg, emptyList(), autoHideMs = 8_000L)
      voice.speak(msg, lang)
      return executor.execute(name, args, w, h)
    }
    return when (verdict.gate) {
      Gate.AUTO -> overlay.whileHidden { executor.execute(name, args, w, h) }
      Gate.CONFIRM -> {
        val yes = ask(prompt.ifBlank { t("confirmGeneric", "Shall I do this?") },
          a.optString("yes_label").ifBlank { t("yes", "Yes") }, t("no", "No"))
        if (!yes && changeRequested) JSONObject().put("ok", false).put("error", "user_changed_plan")
        else if (!yes) JSONObject().put("ok", false).put("error", "user_declined")
        else overlay.whileHidden { executor.execute(name, args, w, h) }.put("safety_acknowledgement", true)
      }
      Gate.PRIVATE -> {
        // Hand over: the user types their PIN/OTP/password themselves. We never see or type it.
        val done = ask(a.optString("ask").ifBlank {
          t("privateStep", "This step is private. Please do it yourself, then tap Continue.")
        }, t("continue", "Continue"), t("cancel", "Cancel"), compact = true)
        if (!done && changeRequested) JSONObject().put("ok", false).put("error", "user_changed_plan")
        else if (!done) { stop(); JSONObject().put("ok", false).put("error", "user_stopped") }
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
      val res = api.post("/v1/screen/explain", JSONObject().put("language", lang).put("screen", screen.toJson()), timeoutMs = SLOW_CALL_MS)
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
      Log.e(TAG, "understandScreen failed", e)
      say(explain(e), finalMessage = true)
    }
  }

  /**
   * "Tell me" from the bubble: one spoken command about whatever is on screen, e.g. "cancel my
   * ride" in Uber or "make the text bigger". Runs as a normal task (plan, approval, gates).
   */
  fun tellMe() = worker.execute {
    if (isRunning) return@execute
    overlay.showPanel(t("tellMePrompt", "What should I do here?"), emptyList())
    voice.speakAndWait(t("tellMePrompt", "What should I do here?"), lang)
    val text = listenWithFeedback().text
    if (text.isNullOrBlank()) return@execute
    overlay.hidePanel()
    startTask(text)
  }

  /**
   * Listens with clear feedback: a beep and "Listening… speak now" with live voice bars, a second
   * beep and "Got it, one moment…" when the user stops, then their words (or why nothing was heard).
   */
  private fun listenWithFeedback(): com.northstar.nextstep.agent.voice.Heard {
    overlay.setState(com.northstar.nextstep.agent.overlay.BubbleState.LISTENING)
    val feedback = overlay.showListening(t("listeningNow", "Listening… speak now"), t("gotItWait", "Got it, one moment…"))
    val heard = voice.listenAndWait(lang, feedback)
    overlay.setState(com.northstar.nextstep.agent.overlay.BubbleState.IDLE)
    val text = heard.text
    if (text.isNullOrBlank()) {
      say(t(when (heard.error) { "mic_silent" -> "micSilent"; "network" -> "networkError"; else -> "didNotHear" }, "I didn't hear that."))
    }
    return heard
  }

  /**
   * "Check for scam" from the bubble: reads the messages on the current screen (any app), asks
   * the scam shield, and tells the user. Offers only the verified official site, never the link
   * from the message.
   */
  fun checkScreenForScam() = worker.execute {
    try {
      overlay.showPanel(t("scamChecking", "Checking this message…"), emptyList())
      val screen = overlay.whileHidden { reader.capture(withScreenshot = false) }
      val res = api.post("/v1/scam/screen", JSONObject().put("language", lang).put("screen", screen.toJson()))
      log("scam_check", res)
      val risky = res.optString("risk") in setOf("high", "medium")
      val buttons = mutableListOf<PanelButton>()
      res.optString("official_url").takeIf { risky && it.startsWith("https://") }?.let { url ->
        buttons += PanelButton(t("openOfficial", "Open official website"), Style.GO) {
          overlay.hidePanel()
          service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
      buttons += PanelButton(t("ok", "OK"), Style.PRIMARY) { voice.stopSpeaking(); overlay.hidePanel() }
      overlay.showPanel(res.getString("warning"), buttons, if (risky) Style.STOP else null)
      voice.speak(res.getString("warning"), lang)
    } catch (e: Exception) {
      Log.e(TAG, "checkScreenForScam failed", e)
      say(explain(e), finalMessage = true)
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
    }.onFailure { Log.e(TAG, "scam check failed", it) }
  }

  /**
   * Medicine photo → WhatsApp. Opens the recipient's chat with the photo and caption attached,
   * then asks the user before tapping Send. If WhatsApp can't jump to the contact, the user picks
   * the chat and sends themselves; NextStep never sends without a yes.
   */
  fun shareImageToWhatsApp(path: String, phone: String?, caption: String, recipient: String) = worker.execute {
    stopped.set(false)
    overlay.setBusy(true)
    try {
      val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { executor.isInstalled(it) }
        ?: run { say(t("whatsappMissing", "WhatsApp is not installed."), finalMessage = true); return@execute }
      val uri = FileProvider.getUriForFile(service, "${service.packageName}.nextstep.files", File(path))
      val jid = phone?.let { whatsappJid(it) }
      service.startActivity(Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        jid?.let { putExtra("jid", it) }
        setPackage(pkg)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      })
      log("share", JSONObject().put("app", pkg).put("recipient", recipient).put("direct", jid != null))

      val isSend = { n: AccessibilityNodeInfo ->
        n.packageName?.toString() == pkg && n.isVisibleToUser &&
          (n.viewIdResourceName == "$pkg:id/send" || n.contentDescription?.toString().equals("Send", true))
      }
      if (jid == null || executor.waitForNode(10_000, isSend) == null) {
        say(t("pickChatYourself", "Choose the chat and tap Send."), finalMessage = true)
        return@execute
      }
      if (stopped.get()) return@execute

      val question = t("confirmSendPhoto", "Send this medicine photo to {name} on WhatsApp?").replace("{name}", recipient)
      if (!ask(question, t("yesSend", "Yes, send"), t("no", "No"))) {
        api.postQuietly("/v1/events", JSONObject().put("kind", "share_declined").put("data", JSONObject().put("recipient", recipient)))
        say(t("notSent", "Okay, I did not send it."), finalMessage = true)
        return@execute
      }
      // Re-find right before tapping: the node from before the question may be stale.
      val sent = overlay.whileHidden {
        executor.waitForNode(3_000, isSend)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
      }
      log("share_sent", JSONObject().put("recipient", recipient).put("ok", sent))
      if (sent) api.postQuietly("/v1/events", JSONObject().put("kind", "share_sent").put("data", JSONObject().put("recipient", recipient)))
      say(if (sent) t("photoSent", "Sent.") else t("pickChatYourself", "Choose the chat and tap Send."), finalMessage = true)
    } catch (e: Exception) {
      Log.e(TAG, "share failed", e)
      log("error", JSONObject().put("message", e.message))
      say(explain(e), finalMessage = true)
    } finally {
      overlay.setBusy(false)
    }
  }

  /** "98765 43210" or "+91 98765-43210" → "919876543210@s.whatsapp.net" (India default). */
  private fun whatsappJid(phone: String): String? {
    val d = phone.filter(Char::isDigit).trimStart('0')
    val full = when {
      d.length == 10 -> "91$d"
      d.length in 11..15 -> d
      else -> return null
    }
    return "$full@s.whatsapp.net"
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
    // Buttons work immediately: a tap answers and cuts the speech short.
    val respond = { yes: Boolean -> if (decision.complete(yes)) voice.stopSpeaking() }
    val buttons = mutableListOf(
      PanelButton(yesLabel, if (compact) Style.PRIMARY else Style.GO) { respond(true) },
      PanelButton(noLabel, if (compact) Style.STOP else Style.OUTLINE) { respond(false) },
    )
    if (taskId != null) buttons += changeButton { changeRequested = true; respond(false) }
    overlay.showPanel(message, buttons, steps = steps)
    overlay.setState(com.northstar.nextstep.agent.overlay.BubbleState.WAITING)
    val spoken = CompletableFuture<Unit>()
    // Only the question is read aloud; the steps are on screen (reading them all made every
    // task start with a long monologue).
    voice.speak(message, lang) { spoken.complete(Unit) }
    runCatching { CompletableFuture.anyOf(spoken, decision).get(60, java.util.concurrent.TimeUnit.SECONDS) }
    if (!compact && !decision.isDone) {
      voice.listen(lang) { heard ->
        val text = heard.text ?: return@listen
        when (val yn = yesNo(text, message)) {
          null -> if (taskId != null && isInstruction(text, message)) {
            // Not a yes/no: the user is telling us something else ("also add bread").
            heardChange = text; changeRequested = true; decision.complete(false)
          }
          else -> decision.complete(yn)
        }
      }
    }
    val answer = runCatching { decision.get() }.getOrDefault(false)
    overlay.setState(com.northstar.nextstep.agent.overlay.BubbleState.IDLE)
    voice.cancelListening()
    pendingDecision = null
    overlay.hidePanel()
    log("decision", JSONObject().put("message", message).put("approved", answer))
    return answer && !stopped.get()
  }

  /** A real request, not noise or NextStep's own voice echoing back into the mic. */
  private fun isInstruction(heard: String, spoken: String): Boolean {
    val words = heard.trim().split(Regex("\\s+")).filter { it.length > 1 }
    if (words.size < 2) return false
    // Split on spaces/punctuation only: \W would break Hindi and Kannada words apart.
    val spokenWords = spoken.lowercase().split(Regex("[\\s,.?!।“”\"]+")).toSet()
    val echoed = words.count { it.lowercase().trim(',', '.', '?', '!') in spokenWords }
    return echoed.toDouble() / words.size < 0.6
  }

  private fun changeButton(onTap: () -> Unit) =
    PanelButton(t("sayChange", "Add or change something"), Style.OUTLINE, com.northstar.nextstep.agent.R.drawable.ic_ns_mic) {
      voice.stopSpeaking(); onTap()
    }

  /**
   * The user wants to change the task mid-way: listen (unless we already heard it), send it to
   * the operator, and confirm back what we understood. Runs on the worker thread.
   */
  /**
   * The user wants to add or change something mid-task. We show and read back what we heard and
   * only send it after a yes ("You said: also add bread. Is that right?"), so a mishearing never
   * silently changes the task. Runs on the worker thread.
   */
  private fun takeChange(id: String, announce: Boolean = true): String? {
    changeRequested = false
    var text = heardChange ?: listenForChange() ?: return null
    heardChange = null
    if (isNoChange(text)) {
      say(t("noChange", "Okay, no changes. Carrying on."))
      return null
    }
    repeat(3) {
      val ok = ask(t("changeCheck", "You said: “{text}”. Is that right?").replace("{text}", text),
        t("yes", "Yes"), t("tryAgain", "Try again"))
      if (ok) {
        return runCatching { api.post("/v1/tasks/$id/instruct", JSONObject().put("text", text)) }
          .onSuccess { if (announce) say(t("changeHeard", "Got it: “{text}”. I'll do that.").replace("{text}", text)) }
          .onFailure { Log.e(TAG, "instruct failed", it); say(explain(it as? Exception ?: Exception(it))) }
          .map { text }.getOrNull()
      }
      changeRequested = false
      text = heardChange?.also { heardChange = null } ?: listenForChange() ?: return null
    }
    return null
  }

  /** "Nothing", "never mind", "no": the user doesn't want to change anything after all. */
  private fun isNoChange(text: String): Boolean {
    val h = text.lowercase().trim().trimEnd('.', '!', '।')
    val none = prefs.words("noChangeWords", listOf("nothing", "never mind", "no change", "no", "none", "leave it",
      "kuch nahi", "कुछ नहीं", "रहने दो", "ಏನೂ ಇಲ್ಲ", "ಬೇಡ"))
    return none.any { h == it || h.startsWith("$it ") } || h.split(Regex("\\s+")).size <= 2 && none.any { h.hasWord(it) }
  }

  private fun listenForChange(): String? {
    overlay.showPanel(t("sayChangePrompt", "What would you like to add or change?"), emptyList())
    voice.speakAndWait(t("sayChangePrompt", "What would you like to add or change?"), lang)
    return listenWithFeedback().text?.takeIf { it.isNotBlank() }
  }

  /**
   * Yes/no from speech. A "no" word that is part of the question itself doesn't count: to
   * "Cancel your ride?" the answer "yes, cancel it" is a yes.
   */
  private fun yesNo(heard: String, question: String = ""): Boolean? {
    val h = heard.lowercase()
    val q = question.lowercase()
    val yes = prefs.words("yesWords", listOf("yes", "ok", "okay", "sure", "do it", "haan", "ha", "हाँ", "हां", "ಹೌದು", "ಸರಿ"))
    val no = prefs.words("noWords", listOf("no", "stop", "don't", "cancel", "nahin", "नहीं", "ಬೇಡ", "ಇಲ್ಲ"))
      .filter { !q.hasWord(it) || it in setOf("no", "nahin", "नहीं", "ಬೇಡ", "ಇಲ್ಲ") }
    return when {
      no.any { h.hasWord(it) } -> false // "no" wins on ambiguity
      yes.any { h.hasWord(it) } -> true
      else -> null
    }
  }

  /** Whole-word match ("no" must not match "now" or "know"); works for any script. */
  private fun String.hasWord(w: String) =
    Regex("(^|[\\s,.!?।])" + Regex.escape(w) + "($|[\\s,.!?।])").containsMatchIn(this)

  /**
   * Shows and speaks a message. Every message has an OK button; informational ones also close
   * themselves a few seconds after being spoken, so nothing is ever left stuck on screen.
   */
  private fun say(message: String, finalMessage: Boolean = false) {
    if (message.isBlank()) return
    val dismissed = CompletableFuture<Unit>()
    overlay.showPanel(message, listOf(PanelButton(t("ok", "OK"), Style.PRIMARY) {
      voice.stopSpeaking(); overlay.hidePanel(); dismissed.complete(Unit)
    }), autoHideMs = if (finalMessage) null else 6_000L)
    val spoken = CompletableFuture<Unit>()
    voice.speak(message, lang) { spoken.complete(Unit) }
    // Continue as soon as it's spoken or the user taps OK.
    runCatching { CompletableFuture.anyOf(spoken, dismissed).get(30, java.util.concurrent.TimeUnit.SECONDS) }
  }

  private fun log(kind: String, payload: JSONObject) =
    AgentBus.emit("agent", JSONObject().put("kind", kind).put("task_id", taskId).put("data", payload))

  private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
  private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

  companion object {
    const val MAX_TURNS = 40
    private const val BATCH_GAP_MS = 400L
    private const val TAG = "NextStepAgent"
    /** Planning and operator steps; the server answers within its 75 s budget. */
    private const val SLOW_CALL_MS = 90_000
  }
}
