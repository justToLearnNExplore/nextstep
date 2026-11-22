package com.northstar.nextstep.agent.screen

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import java.util.concurrent.TimeUnit

/**
 * Enhanced security (upon mentorship): ML Kit's on-device entity detector finds phone numbers,
 * card numbers and bank account numbers (IBAN) in any format, so they can be hidden before the
 * screen leaves the phone. Runs fully on the phone; nothing is sent to Google for this.
 *
 * It is an extra layer on top of [PrivacyFilter]'s fixed rules and never blocks the helper:
 *  - the small language model downloads once in the background; until it is ready, or if it
 *    fails, is slow or errors, the fixed rules still apply and the screen goes out as before;
 *  - it only runs when the screen contains a long number at all, so most screens cost nothing.
 * Addresses are intentionally not detected: the helper must read them to book rides and orders.
 */
object EntityMasker {
  private const val TAG = "NextStepPrivacy"
  private val TYPES = setOf(Entity.TYPE_PHONE, Entity.TYPE_PAYMENT_CARD, Entity.TYPE_IBAN)

  private val extractor by lazy {
    EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build())
  }

  @Volatile private var ready = false
  @Volatile private var preparing = false

  /** Starts the one-time model download in the background. Safe to call often. */
  fun prepare() {
    if (ready || preparing) return
    preparing = true
    runCatching {
      extractor.downloadModelIfNeeded()
        .addOnSuccessListener { ready = true; preparing = false; Log.i(TAG, "entity model ready") }
        .addOnFailureListener { e -> preparing = false; Log.w(TAG, "entity model not ready: ${e.message}") }
    }.onFailure { preparing = false }
  }

  /**
   * Character ranges in [text] that hold a phone, card or IBAN number. Empty when the model is
   * not ready yet or takes longer than [timeoutMs]. Must be called off the main thread.
   */
  fun find(text: String, timeoutMs: Long = 300): List<IntRange> {
    if (!ready) { prepare(); return emptyList() }
    return runCatching {
      val params = EntityExtractionParams.Builder(text).setEntityTypesFilter(TYPES).build()
      Tasks.await(extractor.annotate(params), timeoutMs, TimeUnit.MILLISECONDS)
        .filter { a -> a.entities.any { it.type in TYPES } }
        .map { it.start until it.end }
    }.getOrElse { e ->
      Log.w(TAG, "entity check skipped: ${e.javaClass.simpleName}")
      emptyList()
    }
  }
}
