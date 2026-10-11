package com.northstar.nextstep.agent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.northstar.nextstep.agent.notify.NotificationWatcher

/**
 * "Phone help": sound problems and "what did I miss". Changes are only made after the senior says
 * yes in the app; reading happens on the phone and is summarised by the backend without storage.
 */
class PhoneHelp(private val context: Context) {
  private val audio = context.getSystemService(AudioManager::class.java)
  private val notifications = context.getSystemService(NotificationManager::class.java)

  fun audioState(): Map<String, Any> = mapOf(
    "mediaVolume" to audio.getStreamVolume(AudioManager.STREAM_MUSIC),
    "mediaMax" to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
    "ringVolume" to audio.getStreamVolume(AudioManager.STREAM_RING),
    "ringMax" to audio.getStreamMaxVolume(AudioManager.STREAM_RING),
    "ringerMode" to when (audio.ringerMode) {
      AudioManager.RINGER_MODE_SILENT -> "silent"
      AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
      else -> "normal"
    },
    "doNotDisturb" to (notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL),
    "policyAccess" to notifications.isNotificationPolicyAccessGranted,
  )

  /**
   * kind = "media": media volume to ~70% (and unmuted). kind = "ringer": ring mode normal, ring
   * volume ~70%, Do Not Disturb off. Returns "ok" or "needs_access" (Android requires the user to
   * allow NextStep to change Do Not Disturb / silent mode first).
   */
  fun fixSound(kind: String): String {
    return try {
      if (kind == "media") {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.7).toInt(), AudioManager.FLAG_SHOW_UI)
      } else {
        val dnd = notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        val silent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        if ((dnd || silent) && !notifications.isNotificationPolicyAccessGranted) return "needs_access"
        if (dnd) notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, (audio.getStreamMaxVolume(AudioManager.STREAM_RING) * 0.7).toInt(), AudioManager.FLAG_SHOW_UI)
      }
      "ok"
    } catch (e: SecurityException) {
      "needs_access"
    }
  }

  private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

  /** Missed calls, SMS and messaging notifications since [sinceMs]. Missing permissions are skipped. */
  fun recentActivity(sinceMs: Long): Map<String, Any> {
    val calls = mutableListOf<Map<String, Any?>>()
    if (granted(Manifest.permission.READ_CALL_LOG)) {
      context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
        "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
        arrayOf(CallLog.Calls.MISSED_TYPE.toString(), sinceMs.toString()),
        "${CallLog.Calls.DATE} DESC",
      )?.use { c ->
        while (c.moveToNext() && calls.size < 30) {
          calls += mapOf("number" to c.getString(0), "name" to c.getString(1), "at" to c.getLong(2))
        }
      }
    }
    val messages = mutableListOf<Map<String, Any?>>()
    if (granted(Manifest.permission.READ_SMS)) {
      context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
        "${Telephony.Sms.DATE} > ?", arrayOf(sinceMs.toString()), "${Telephony.Sms.DATE} DESC",
      )?.use { c ->
        while (c.moveToNext() && messages.size < 30) {
          messages += mapOf("app" to "SMS", "from" to contactName(c.getString(0)), "text" to c.getString(1), "at" to c.getLong(2))
        }
      }
    }
    messages += NotificationWatcher.recentMessages(sinceMs)
    return mapOf(
      "calls" to calls,
      "messages" to messages,
      "callLogAllowed" to granted(Manifest.permission.READ_CALL_LOG),
      "smsAllowed" to granted(Manifest.permission.READ_SMS),
    )
  }

  private fun contactName(number: String?): String? {
    if (number == null || !granted(Manifest.permission.READ_CONTACTS)) return number
    val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
    return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
      ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: number
  }
}
