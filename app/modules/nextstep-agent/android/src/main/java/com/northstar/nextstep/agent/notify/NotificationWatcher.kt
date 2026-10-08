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

  private val recent = LinkedHashSet<String>()

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (!Prefs(this).scamCheckEnabled || sbn.packageName !in MESSAGE_APPS) return
    if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
    val extras = sbn.notification.extras
    val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
      ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
    if (text.length < 12) return

    // Messaging apps re-post the same notification; check each message once.
    val key = "${sbn.packageName}|$sender|${text.hashCode()}"
    if (!recent.add(key)) return
    if (recent.size > 200) recent.remove(recent.first())

    NextStepAccessibilityService.instance?.runner?.checkMessage(sbn.packageName, sender, text)
  }

  companion object {
    val MESSAGE_APPS = setOf(
      "com.whatsapp", "com.whatsapp.w4b",
      "com.google.android.apps.messaging", "com.samsung.android.messaging",
      "com.android.mms", "com.truecaller",
    )
  }
}
