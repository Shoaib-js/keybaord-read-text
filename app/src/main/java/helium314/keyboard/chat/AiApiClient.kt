package helium314.keyboard.chat

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AiApiClient
 *
 * Interface for sending a prompt to an AI backend and receiving reply suggestions.
 * Keeping this as an interface makes [AiSuggestionEngine] testable — in tests you
 * can inject a mock that returns hardcoded suggestions without network calls.
 *
 * The only production implementation is [BackendAiApiClient].
 *
 * ─── Why this was missing ────────────────────────────────────────
 * AiSuggestionEngine.kt referenced AiApiClient but the interface was
 * never created, causing:
 *   "Unresolved reference: AiApiClient"
 *   "Unresolved reference: complete"
 * This file is the fix.
 * ─────────────────────────────────────────────────────────────────
 */
interface AiApiClient {
    /**
     * Send [prompt] to the AI and return a list of reply suggestion strings.
     * Must be called from a background thread (IO dispatcher).
     * Returns an empty list on any error — never throws.
     */
    fun complete(prompt: String): List<String>
}

// ─────────────────────────────────────────────────────────────────────────────
// DEFAULT IMPLEMENTATION — calls your existing backend HTTP endpoint
// Same server that ChatAiSuggestionHelper already uses.
//
// Expected request:
//   POST <backendUrl>
//   Content-Type: application/json
//   {"prompt":"...full conversation prompt...","app":"whatsapp","tone":"Smart"}
//
// Expected response:
//   {"status":"success","suggestions":["Haan sure","Kal milte hain","Ok bhai"]}
// ─────────────────────────────────────────────────────────────────────────────

/**
 * @param backendUrl  Your server URL.  e.g. "https://yourserver.com/generate-reply"
 * @param app         Source app: "whatsapp" or "instagram"
 * @param tone        Selected tone: "Smart", "Funny", "Flirty", etc.
 */
class BackendAiApiClient(
    private val backendUrl: String,
    private val app:  String = "whatsapp",
    private val tone: String = "Smart"
) : AiApiClient {

    companion object {
        private const val TAG             = "AiApiClient"
        private const val CONNECT_TIMEOUT = 10_000   // 10 s
        private const val READ_TIMEOUT    = 20_000   // 20 s
    }

    override fun complete(prompt: String): List<String> {
        return try {
            val payload  = buildPayload(prompt)
            val response = post(payload)
            parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "complete() failed: ${e.message}")
            emptyList()
        }
    }

    // ── Build JSON request body ──────────────────────────────────────────────
    private fun buildPayload(prompt: String): String {
        val escaped = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"prompt":"$escaped","app":"$app","tone":"$tone"}"""
    }

    // ── HTTP POST ────────────────────────────────────────────────────────────
    private fun post(payload: String): String {
        val conn = URL(backendUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput       = true
            conn.doInput        = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "no body"
                throw Exception("HTTP $code: $errBody")
            }

            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    // ── Parse {"status":"success","suggestions":["a","b","c"]} ──────────────
    private fun parseResponse(json: String): List<String> {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("status", "") != "success") {
                Log.w(TAG, "Backend returned status=${obj.optString("status")} | body=$json")
                return emptyList()
            }
            val arr    = obj.getJSONArray("suggestions")
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotEmpty()) result.add(s)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "parseResponse failed: ${e.message} | raw=$json")
            emptyList()
        }
    }
}
