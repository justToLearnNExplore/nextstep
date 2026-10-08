package com.northstar.nextstep.agent.safety

import org.json.JSONArray
import org.json.JSONObject

/**
 * How much human involvement an action needs. Ordered: a gate can only be raised, never lowered.
 *  AUTO     – routine step covered by the task-level consent (tap, search, scroll, back).
 *  CONFIRM  – sensitive/irreversible: user must say or tap "yes" first (send, order, install, pay page).
 *  PRIVATE  – user-only: passwords, PINs, OTPs, biometrics, UPI, card data. Agent hands over and waits.
 *  BLOCKED  – never allowed (e.g. tapping a link flagged as a scam).
 */
enum class Gate { AUTO, CONFIRM, PRIVATE, BLOCKED;
  companion object {
    fun parse(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: CONFIRM
  }
}

data class Verdict(val gate: Gate, val reason: String)

fun maxOf(a: Verdict, b: Verdict) = if (b.gate.ordinal > a.gate.ordinal) b else a

/**
 * On-device, deterministic second line of defence. The backend Guardian classifies every action
 * first; this gate re-checks it against the live accessibility tree right before execution and can
 * only escalate. It works even if the model or network misbehaves.
 */
object SafetyGate {

  private val PAYMENT_APPS = setOf(
    "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user",
    "in.org.npci.upiapp", "in.amazon.mShop.android.shopping.upi",
  )

  // Labels on buttons whose tap is irreversible. Kept multilingual; extend per language pack.
  private val IRREVERSIBLE = Regex(
    "(place order|confirm order|order now|pay |pay$|^pay|proceed to pay|make payment|send|share|" +
      "install|uninstall|delete|remove account|transfer|buy now|submit|add address|save address|" +
      "भेजें|भेजो|ऑर्डर करें|भुगतान|इंस्टॉल|हटाएं|" +
      "ಕಳುಹಿಸು|ಕಳುಹಿಸಿ|ಆರ್ಡರ್ ಮಾಡಿ|ಪಾವತಿ|ಸ್ಥಾಪಿಸು)",
    RegexOption.IGNORE_CASE,
  )

  private val SECRET_FIELD = Regex(
    "(password|passcode|pass code|\\bpin\\b|mpin|upi pin|otp|one.time|verification code|cvv|cvc|" +
      "card number|expiry|aadhaar|पासवर्ड|पिन|ओटीपी|ಪಾಸ್‌ವರ್ಡ್|ಪಿನ್|ಒಟಿಪಿ)",
    RegexOption.IGNORE_CASE,
  )

  fun check(name: String, args: JSONObject, pkg: String, nodes: JSONArray, w: Int, h: Int): Verdict {
    if (pkg in PAYMENT_APPS) return Verdict(Gate.PRIVATE, "payment_app")

    return when (name) {
      "click", "long_press" -> {
        val x = args.optInt("x", -1) * w / 1000
        val y = args.optInt("y", -1) * h / 1000
        // Everything under the finger that is smaller than a quarter of the screen: the tapped
        // view plus its label/container, so "Place order" on a parent still counts.
        val hit = nodesAt(nodes, x, y, maxArea = w.toLong() * h / 4)
        if (hit.isEmpty()) return Verdict(Gate.AUTO, "no_node")
        val label = hit.joinToString(" ") { label(it) }
        when {
          hit.any { it.optBoolean("secure") } ||
            (hit.any { it.optBoolean("edit") } && SECRET_FIELD.containsMatchIn(label)) ->
            Verdict(Gate.PRIVATE, "secret_field")
          IRREVERSIBLE.containsMatchIn(label) -> Verdict(Gate.CONFIRM, "irreversible:$label")
          else -> Verdict(Gate.AUTO, "routine")
        }
      }
      "type" -> {
        val focused = (0 until nodes.length()).map { nodes.getJSONObject(it) }
          .firstOrNull { it.optBoolean("focus") && it.optBoolean("edit") }
        when {
          focused == null -> Verdict(Gate.AUTO, "no_focus")
          focused.optBoolean("secure") || SECRET_FIELD.containsMatchIn(label(focused)) ->
            Verdict(Gate.PRIVATE, "secret_field")
          else -> Verdict(Gate.AUTO, "routine")
        }
      }
      "press_key" ->
        if (args.optString("key").equals("enter", true) && screenMentionsSecret(nodes))
          Verdict(Gate.PRIVATE, "secret_screen") else Verdict(Gate.AUTO, "routine")
      else -> Verdict(Gate.AUTO, "routine")
    }
  }

  private fun label(n: JSONObject) =
    listOf("text", "desc", "id", "hint").mapNotNull { n.optString(it).takeIf(String::isNotBlank) }
      .joinToString(" ")

  private fun screenMentionsSecret(nodes: JSONArray) =
    (0 until nodes.length()).any { SECRET_FIELD.containsMatchIn(label(nodes.getJSONObject(it))) }

  private fun nodesAt(nodes: JSONArray, x: Int, y: Int, maxArea: Long): List<JSONObject> =
    (0 until nodes.length()).map { nodes.getJSONObject(it) }.filter { n ->
      val b = n.getJSONArray("b")
      val l = b.getInt(0); val t = b.getInt(1); val r = b.getInt(2); val bt = b.getInt(3)
      x in l..r && y in t..bt && (r - l).toLong() * (bt - t) <= maxArea
    }
}
