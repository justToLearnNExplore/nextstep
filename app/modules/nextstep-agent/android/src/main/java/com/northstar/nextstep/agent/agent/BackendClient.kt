package com.northstar.nextstep.agent.agent

import com.northstar.nextstep.agent.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal JSON client for the NextStep Cloud Run backend. Called from worker threads only.
 * Identity: Firebase ID token (refreshed here, since tasks run while the RN app is in the
 * background), or a dev device id when Firebase is not configured.
 */
class BackendClient(private val prefs: Prefs) {

  fun post(path: String, body: JSONObject, timeoutMs: Int = 60_000): JSONObject {
    val conn = (URL(prefs.apiBaseUrl + path).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 10_000
      readTimeout = timeoutMs
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept-Language", prefs.language)
      val token = freshToken()
      if (token != null) setRequestProperty("Authorization", "Bearer $token")
      else prefs.deviceId?.let { setRequestProperty("X-Device-Id", it) }
    }
    try {
      conn.outputStream.use { it.write(body.toString().toByteArray()) }
      val code = conn.responseCode
      val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw BackendError(code, text.take(300))
      return JSONObject(text)
    } finally {
      conn.disconnect()
    }
  }

  /** Best-effort fire-and-forget (family timeline events). */
  fun postQuietly(path: String, body: JSONObject) {
    runCatching { post(path, body, timeoutMs = 10_000) }
  }

  @Synchronized
  private fun freshToken(): String? {
    val key = prefs.firebaseApiKey ?: return null
    val refresh = prefs.refreshToken ?: return null
    val current = prefs.authToken
    if (current != null && prefs.tokenExpiresAt - System.currentTimeMillis() > 5 * 60_000) return current
    val conn = (URL("https://securetoken.googleapis.com/v1/token?key=$key").openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = 10_000
      readTimeout = 10_000
      setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    return try {
      conn.outputStream.use {
        it.write("grant_type=refresh_token&refresh_token=${URLEncoder.encode(refresh, "UTF-8")}".toByteArray())
      }
      if (conn.responseCode !in 200..299) return current
      val r = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
      prefs.authToken = r.getString("id_token")
      prefs.refreshToken = r.getString("refresh_token")
      prefs.tokenExpiresAt = System.currentTimeMillis() + r.getString("expires_in").toLong() * 1000
      prefs.authToken
    } catch (_: Exception) {
      current
    } finally {
      conn.disconnect()
    }
  }
}

class BackendError(val code: Int, body: String) : Exception("HTTP $code: $body")
