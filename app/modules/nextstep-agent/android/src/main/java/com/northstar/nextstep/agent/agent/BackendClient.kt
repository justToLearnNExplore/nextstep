package com.northstar.nextstep.agent.agent

import com.northstar.nextstep.agent.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON client for the NextStep Cloud Run backend. Called from worker threads only. */
class BackendClient(private val prefs: Prefs) {

  fun post(path: String, body: JSONObject, timeoutMs: Int = 60_000): JSONObject {
    val conn = (URL(prefs.apiBaseUrl + path).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 10_000
      readTimeout = timeoutMs
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept-Language", prefs.language)
      prefs.authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
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
}

class BackendError(val code: Int, body: String) : Exception("HTTP $code: $body")
