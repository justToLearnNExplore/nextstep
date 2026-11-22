package com.northstar.nextstep.agent.screen

/**
 * Enhanced security (upon mentorship): hides sensitive VALUES on the phone, before the screen
 * is sent anywhere.
 *
 * What it hides:
 *  1. The value inside a box whose own name says password / PIN / OTP / CVV / card number…,
 *     even when the app forgot to mark it as a password box.
 *  2. Numbers that look like secrets: OTP codes next to words like "OTP" or "verification code",
 *     short number-only entries on login / OTP screens, card numbers (Luhn-checked) and Aadhaar
 *     numbers (Verhoeff-checked). Card and Aadhaar keep their last 4 digits.
 *  3. Phone numbers in read-only text (keeps the last 4 digits). ML Kit's on-device entity
 *     detector adds to this in [EntityMasker]; this file is the always-available fallback.
 *
 * What it deliberately does NOT change, so nothing that works today breaks:
 *  - Labels, hints and ids. The safety rules match words like "OTP" or "password" in them, so
 *    every gate decision stays exactly the same.
 *  - Addresses. NextStep must read pickup, drop and delivery addresses to book rides and orders.
 *  - Text the helper itself typed into an ordinary box (it needs to see that typing worked).
 *
 * Pure Kotlin with no Android types, so it is covered by plain JVM unit tests.
 */
object PrivacyFilter {

  /** What a hidden value is replaced with. Fixed length, so it never reveals how long it was. */
  const val MASK = "••••"

  /**
   * Words that mark a private box (English, Hindi, Kannada). The phone's SafetyGate uses this same
   * list, so "hidden" and "user-only" always agree.
   */
  val SECRET_WORDS = Regex(
    "(password|passcode|pass code|\\bpin\\b|mpin|upi pin|otp|one.time|verification code|cvv|cvc|" +
      "card number|expiry|aadhaar|पासवर्ड|पिन|ओटीपी|ಪಾಸ್‌ವರ್ಡ್|ಪಿನ್|ಒಟಿಪಿ)",
    RegexOption.IGNORE_CASE,
  )

  // A code is 4-8 digits, or WhatsApp-style "123-456".
  private const val CODE = "(\\d{4,8}|\\d{3}[- ]\\d{3})"
  // "\b" keeps "Pincode: 560001" (an address) from being read as "code: …".
  private const val CODE_WORDS =
    "\\botp|one[ -]?time (?:password|code|pin)|verification code|security code|login code|" +
      "auth(?:entication)? code|passcode|password|\\bcode is|\\bcode:|ओटीपी|ಒಟಿಪಿ"

  /**
   * Stronger words for "this is a login / OTP screen". Plain "PIN" is left out on purpose:
   * address forms say "PIN code", and house numbers typed there must stay readable.
   */
  private val LOGIN_WORDS = Regex(
    "(password|passcode|pass code|mpin|upi pin|\\botp|one.time|verification code|cvv|cvc|" +
      "पासवर्ड|ओटीपी|ಪಾಸ್‌ವರ್ಡ್|ಒಟಿಪಿ)",
    RegexOption.IGNORE_CASE,
  )

  /** "Your OTP is 482913", "Verification code: 1234", "WhatsApp code 123-456". */
  private val CODE_AFTER_WORD = Regex(
    "(?:$CODE_WORDS)[^\\d\\n]{0,40}?(?<![\\d•])$CODE(?!\\d)",
    RegexOption.IGNORE_CASE,
  )

  /** "482913 is your OTP", "G-123456 is your Google verification code". */
  private val CODE_BEFORE_WORD = Regex(
    "(?<![\\d•])$CODE(?!\\d)[^\\d\\n]{0,30}?(?:$CODE_WORDS)",
    RegexOption.IGNORE_CASE,
  )

  /** 13-19 digits, optionally grouped with spaces or dashes. */
  private val CARD = Regex("(?<![\\d•])\\d(?:[ -]?\\d){12,18}(?!\\d)")

  /** 12 digits as Aadhaar prints them (never starts with 0 or 1). */
  private val AADHAAR = Regex("(?<![\\d•])[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?![ -]?\\d)")

  /** Indian mobile numbers: optional +91 / 0, then 10 digits starting 6-9. */
  private val PHONE = Regex("(?<![\\d•+])(?:\\+91[ -]?|0)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)")

  /** A number-only entry such as a PIN (4-8 digits) or one box of a split OTP field (1 digit). */
  private val SHORT_DIGITS = Regex("^\\s*(\\d|\\d{4,8})\\s*$")

  /** Cheap pre-check before asking ML Kit: is there any run of 8+ digits at all? */
  private val LONG_NUMBER = Regex("\\d(?:[ ()+.-]{0,2}\\d){7,}")

  /** True when a label shows this is a login / OTP / password screen. */
  fun isLoginLabel(label: String): Boolean = LOGIN_WORDS.containsMatchIn(label)

  fun mayHoldLongNumber(text: String): Boolean = LONG_NUMBER.containsMatchIn(text)

  /**
   * Hides OTP codes, card numbers, Aadhaar numbers and (when [phones]) phone numbers inside any
   * text. Returns the text unchanged when there is nothing to hide.
   */
  fun maskText(text: String?, phones: Boolean): String? {
    if (text.isNullOrBlank()) return text
    var out = text
    out = replaceGroup(CODE_AFTER_WORD, out) { MASK }
    out = replaceGroup(CODE_BEFORE_WORD, out) { MASK }
    // Cards first, so the first 12 digits of a card are never read as an Aadhaar number.
    out = CARD.replace(out) { m -> if (luhn(digits(m.value))) "•••• " + digits(m.value).takeLast(4) else m.value }
    out = AADHAAR.replace(out) { m -> if (verhoeff(digits(m.value))) "•••• •••• " + digits(m.value).takeLast(4) else m.value }
    if (phones) out = PHONE.replace(out) { m -> keepLast4(m.value) }
    return out
  }

  /**
   * The value shown inside an editable box, after hiding what must not leave the phone.
   *  - [secretScreen]: the screen has a password box, or a label mentions password / OTP / MPIN.
   * Text that is really the box's own label (same as the hint, or itself says "OTP" / "Password")
   * is kept, so the helper can still tell an empty box from a filled one.
   */
  fun boxValue(text: String?, desc: String?, id: String?, hint: String?, secretScreen: Boolean): String? {
    if (text.isNullOrBlank()) return text
    if (text == hint || SECRET_WORDS.containsMatchIn(text)) return maskText(text, phones = false)
    // Rule 1: the box's own name says it is private, even if the app forgot the password flag.
    if (listOfNotNull(desc, id, hint).any { SECRET_WORDS.containsMatchIn(it) }) return MASK
    // Rule 2: a number-only entry on a login / OTP screen is a PIN or OTP.
    if (secretScreen && SHORT_DIGITS.matches(text)) return MASK
    return maskText(text, phones = false)
  }

  /** Hides the given character ranges (from ML Kit), keeping the last 4 digits of each. */
  fun maskSpans(text: String, spans: List<IntRange>): String {
    var out = text
    for (s in spans.filter { it.first >= 0 && it.last < text.length }.sortedByDescending { it.first }) {
      val part = out.substring(s.first, s.last + 1)
      if (digits(part).length < 7) continue // not a full phone / card number
      out = out.substring(0, s.first) + keepLast4(part) + out.substring(s.last + 1)
    }
    return out
  }

  // ---- helpers ---------------------------------------------------------------------------

  private fun digits(s: String) = s.filter(Char::isDigit)

  private fun keepLast4(s: String) = "••••••" + digits(s).takeLast(4)

  /** Replaces only capture group 1 of each match, leaving the surrounding words in place. */
  private inline fun replaceGroup(re: Regex, text: String, crossinline with: (String) -> String): String =
    re.replace(text) { m ->
      val g = m.groups[1] ?: return@replace m.value
      val from = g.range.first - m.range.first
      val to = g.range.last + 1 - m.range.first
      m.value.substring(0, from) + with(g.value) + m.value.substring(to)
    }

  /** Card-number checksum: random long numbers (order ids) usually fail it. */
  internal fun luhn(d: String): Boolean {
    if (d.length < 13) return false
    var sum = 0
    d.reversed().forEachIndexed { i, ch ->
      var v = ch - '0'
      if (i % 2 == 1) { v *= 2; if (v > 9) v -= 9 }
      sum += v
    }
    return sum % 10 == 0
  }

  private val VD = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
    intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
    intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
    intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
    intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
  )
  private val VP = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
    intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
    intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
    intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
  )

  /** Aadhaar checksum (Verhoeff), so ordinary 12-digit numbers are left alone. */
  internal fun verhoeff(d: String): Boolean {
    if (d.length != 12) return false
    var c = 0
    d.reversed().forEachIndexed { i, ch -> c = VD[c][VP[i % 8][ch - '0']] }
    return c == 0
  }
}
