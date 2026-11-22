package com.northstar.nextstep.agent.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enhanced security (upon mentorship): what is hidden, and what must stay exactly as it was. */
class PrivacyFilterTest {

  private fun mask(s: String, phones: Boolean = true) = PrivacyFilter.maskText(s, phones)

  // ---- hidden -----------------------------------------------------------------------------

  @Test fun otpCodesAreHidden() {
    assertEquals("Your OTP is ••••. Do not share it.", mask("Your OTP is 482913. Do not share it."))
    assertEquals("•••• is your verification code", mask("482913 is your verification code"))
    assertEquals("Verification code: ••••", mask("Verification code: 1234"))
    assertEquals("Your WhatsApp code is ••••", mask("Your WhatsApp code is 123-456"))
    assertEquals("आपका ओटीपी •••• है", mask("आपका ओटीपी 482913 है"))
  }

  @Test fun cardNumbersKeepLast4() {
    assertEquals("Card •••• 1111", mask("Card 4111 1111 1111 1111"))
    assertEquals("•••• 1111", mask("4111-1111-1111-1111"))
  }

  @Test fun aadhaarKeepsLast4() {
    assertEquals("Aadhaar •••• •••• 0124", mask("Aadhaar 2345 6789 0124"))
  }

  @Test fun phoneNumbersKeepLast4() {
    assertEquals("Call ••••••3210", mask("Call +91 98765 43210"))
    assertEquals("••••••3210", mask("9876543210"))
    // Notifications keep phone numbers (scam checks use them).
    assertEquals("Call +91 98765 43210", mask("Call +91 98765 43210", phones = false))
  }

  @Test fun privateBoxValueIsHiddenEvenWithoutPasswordFlag() {
    // The app forgot to mark the box as a password box, but its hint / id says what it is.
    assertEquals(PrivacyFilter.MASK, PrivacyFilter.boxValue("hunter2", null, "et_password", null, false))
    assertEquals(PrivacyFilter.MASK, PrivacyFilter.boxValue("482913", null, null, "Enter OTP", false))
    assertEquals(PrivacyFilter.MASK, PrivacyFilter.boxValue("123", "CVV", null, null, false))
  }

  @Test fun numberOnlyEntryOnLoginScreenIsHidden() {
    assertEquals(PrivacyFilter.MASK, PrivacyFilter.boxValue("4829", null, null, null, secretScreen = true))
    assertEquals(PrivacyFilter.MASK, PrivacyFilter.boxValue("7", null, null, null, secretScreen = true)) // split OTP box
  }

  @Test fun mlKitSpansKeepLast4() {
    val text = "Driver: 080 2345 6789, cab KA01"
    assertEquals("Driver: ••••••6789, cab KA01", PrivacyFilter.maskSpans(text, listOf(8..20)))
  }

  // ---- unchanged (nothing that works today may break) -------------------------------------

  @Test fun everydayTextIsUnchanged() {
    for (s in listOf(
      "Amul Taaza Toned Milk 1 L ₹56", "Add to cart", "Place order", "Pay ₹1,250",
      "12th Main Road, Indiranagar, Bengaluru, Karnataka 560038", "Pincode: 560038", "PIN code 560038",
      "Arriving in 4 mins", "Order #123456", "Search \"milk\"", "Trip 1 of 2026",
      "Card 4111 1111 1111 1112", // fails the card checksum
      "2345 6789 0129", // fails the Aadhaar checksum
    )) assertEquals(s, mask(s))
  }

  @Test fun labelsAndWhatTheHelperTypedAreKept() {
    // Box label shown as text: kept, so an empty box still looks empty.
    assertEquals("Enter OTP", PrivacyFilter.boxValue("Enter OTP", null, null, null, true))
    assertEquals("Password", PrivacyFilter.boxValue("Password", null, null, "Password", true))
    // Ordinary boxes keep what was typed, so the helper can check its own typing.
    assertEquals("milk", PrivacyFilter.boxValue("milk", null, "search", "Search", false))
    assertEquals("12627", PrivacyFilter.boxValue("12627", null, "search", "Search trains", false))
    assertEquals("9876543210", PrivacyFilter.boxValue("9876543210", null, null, "Mobile number", false))
    // House number on an address form that says "PIN code": still readable.
    assertEquals("221", PrivacyFilter.boxValue("221", null, null, "House / flat no.", false))
    assertEquals(null, PrivacyFilter.boxValue(null, null, "et_otp", null, true))
  }

  @Test fun loginScreenWordsIgnoreAddressPinCode() {
    assertTrue(PrivacyFilter.isLoginLabel("Enter the OTP sent to your phone"))
    assertTrue(PrivacyFilter.isLoginLabel("Password"))
    assertFalse(PrivacyFilter.isLoginLabel("PIN code"))
    assertFalse(PrivacyFilter.isLoginLabel("Hotpot restaurant"))
  }

  @Test fun checksums() {
    assertTrue(PrivacyFilter.luhn("4111111111111111"))
    assertFalse(PrivacyFilter.luhn("4111111111111112"))
    assertTrue(PrivacyFilter.verhoeff("234567890124"))
    assertFalse(PrivacyFilter.verhoeff("234567890129"))
  }

  @Test fun longNumberPreCheck() {
    assertTrue(PrivacyFilter.mayHoldLongNumber("call 080 2345 6789"))
    assertFalse(PrivacyFilter.mayHoldLongNumber("Milk 1 L ₹56, arriving in 8 mins"))
  }
}
