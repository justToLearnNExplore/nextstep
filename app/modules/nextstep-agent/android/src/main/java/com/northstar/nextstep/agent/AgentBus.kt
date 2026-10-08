package com.northstar.nextstep.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide event bus between the native agent (service, overlay, notification watcher)
 * and the React Native layer. The Expo module subscribes and forwards events to JS; when the
 * RN app is not running, events are simply dropped (the action log is persisted server-side).
 */
object AgentBus {
  fun interface Listener {
    fun onEvent(type: String, payload: JSONObject)
  }

  private val listeners = CopyOnWriteArrayList<Listener>()

  fun subscribe(l: Listener) = listeners.add(l)
  fun unsubscribe(l: Listener) = listeners.remove(l)

  fun emit(type: String, payload: JSONObject = JSONObject()) {
    listeners.forEach { it.onEvent(type, payload) }
  }
}

/** Small typed wrapper over SharedPreferences. Set from JS during onboarding and settings. */
class Prefs(context: Context) {
  private val sp: SharedPreferences =
    context.applicationContext.getSharedPreferences("nextstep", Context.MODE_PRIVATE)

  var apiBaseUrl: String
    get() = sp.getString("apiBaseUrl", "http://10.0.2.2:8080")!!
    set(v) = sp.edit().putString("apiBaseUrl", v.trimEnd('/')).apply()

  /** BCP-47 tag, e.g. "en-IN", "hi-IN", "kn-IN". New languages need no native change. */
  var language: String
    get() = sp.getString("language", "en-IN")!!
    set(v) = sp.edit().putString("language", v).apply()

  /** Firebase ID token forwarded to the backend. Refreshed by JS. */
  var authToken: String?
    get() = sp.getString("authToken", null)
    set(v) = sp.edit().putString("authToken", v).apply()

  /** Overlay strings for the current language, pushed from the JS locale pack. */
  var labelsJson: String
    get() = sp.getString("labels", "{}")!!
    set(v) = sp.edit().putString("labels", v).apply()

  var scamCheckEnabled: Boolean
    get() = sp.getBoolean("scamCheckEnabled", false)
    set(v) = sp.edit().putBoolean("scamCheckEnabled", v).apply()

  var overlayEnabled: Boolean
    get() = sp.getBoolean("overlayEnabled", true)
    set(v) = sp.edit().putBoolean("overlayEnabled", v).apply()

  fun label(key: String, fallback: String): String =
    runCatching { JSONObject(labelsJson).optString(key, fallback) }.getOrDefault(fallback)
      .ifBlank { fallback }

  fun words(key: String, fallback: List<String>): List<String> =
    runCatching {
      val arr = JSONObject(labelsJson).optJSONArray(key) ?: return fallback
      (0 until arr.length()).map { arr.getString(it).lowercase() }
    }.getOrDefault(fallback)
}
