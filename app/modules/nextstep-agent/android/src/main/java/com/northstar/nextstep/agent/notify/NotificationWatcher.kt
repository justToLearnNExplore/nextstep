package com.northstar.nextstep.agent.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.northstar.nextstep.agent.NextStepAccessibilityService
import com.northstar.nextstep.agent.Prefs

/**
 * Scam shield input. Only runs when the user has granted notification access AND opted in inside
 * NextStep. Only message apps are inspected; nothing is clicked, replied to, deleted or blocked.
 */
class NotificationWatcher : NotificationListenerService() {

  private val seen = LinkedHashSet<String>()

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName !in MESSAGE_APPS) return
    if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
    val extras = sbn.notification.extras
    val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
      ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
    // For "What did I miss?": kept in memory only, for one hour.
    remember(sbn.packageName, sender, text, sbn.postTime)
    if (!Prefs(this).scamCheckEnabled || text.length < 12) return

    // Messaging apps re-post the same notification; check each message once.
    val key = "${sbn.packageName}|$sender|${text.hashCode()}"
    if (!seen.add(key)) return
    if (seen.size > 200) seen.remove(seen.first())

    NextStepAccessibilityService.instance?.runner?.checkMessage(sbn.packageName, sender, text)
  }

  companion object {
    private const val HOUR_MS = 60 * 60 * 1000L
    private val recent = ArrayDeque<Map<String, Any?>>()

    @Synchronized private fun remember(pkg: String, sender: String, text: String, at: Long) {
      val app = if (pkg.startsWith("com.whatsapp")) "WhatsApp" else "Message"
      recent.addLast(mapOf("app" to app, "from" to sender, "text" to text.take(300), "at" to at))
      val cutoff = System.currentTimeMillis() - HOUR_MS
      while (recent.isNotEmpty() && ((recent.first()["at"] as Long) < cutoff || recent.size > 60)) recent.removeFirst()
    }

    @Synchronized fun recentMessages(sinceMs: Long): List<Map<String, Any?>> =
      recent.filter { (it["at"] as Long) > sinceMs }

    val MESSAGE_APPS = setOf(
      "com.whatsapp", "com.whatsapp.w4b",
      "com.google.android.apps.messaging", "com.samsung.android.messaging",
      "com.android.mms", "com.truecaller",
    )
  }
}
