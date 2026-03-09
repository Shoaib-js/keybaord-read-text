package helium314.keyboard.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import helium314.keyboard.accessibility.ChatAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ChatAiSuggestionHelper
 *
 * Listens for chat context broadcasts from ChatAccessibilityService,
 * formats the conversation, and requests reply suggestions from your
 * configured AI provider (Gemini or OpenAI-compatible).
 *
 * USAGE in your keyboard service or InputMethodService:
 *
 *   val aiHelper = ChatAiSuggestionHelper(context, apiKey, AiProvider.GEMINI) { suggestions ->
 *       // suggestions: List<String> — show these in your suggestion bar
 *       updateSuggestionStrip(suggestions)
 *   }
 *   aiHelper.register()  // Start listening
 *   aiHelper.unregister() // Stop when keyboard is hidden
 */
class ChatAiSuggestionHelper(
    private val context: Context,
    private val apiKey: String,
    private val provider: AiProvider = AiProvider.GEMINI,
    private val onSuggestionsReady: (List<String>) -> Unit
) {

    companion object {
        private const val TAG = "ChatAiSuggestion"

        // Gemini endpoint
        private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val GEMINI_MODEL = "gemini-2.0-flash"

        // OpenAI-compatible endpoint (also works with Groq, OpenRouter, etc.)
        private const val OPENAI_BASE = "https://api.openai.com/v1/chat/completions"
        private const val OPENAI_MODEL = "gpt-4o-mini"

        // For Groq — change base URL only:
        // private const val GROQ_BASE = "https://api.groq.com/openai/v1/chat/completions"
        // private const val GROQ_MODEL = "llama3-8b-8192"
    }

    enum class AiProvider { GEMINI, OPENAI, GROQ }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastMessages: Array<String>? = null

    // ─────────────────────────────────────────────
    // BroadcastReceiver — receives from AccessibilityService
    // ─────────────────────────────────────────────

    private val chatContextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ChatAccessibilityService.ACTION_CHAT_CONTEXT) return

            val messages = intent.getStringArrayExtra(ChatAccessibilityService.EXTRA_MESSAGES)
            val sourcePackage = intent.getStringExtra(ChatAccessibilityService.EXTRA_PACKAGE)

            if (messages != null && messages.isNotEmpty()) {
                lastMessages = messages
                Log.d(TAG, "Received ${messages.size} messages from $sourcePackage")
                // Don't auto-call AI here — wait for user to tap "Suggest" button
                // or trigger from your keyboard's suggestion logic
            }
        }
    }

    // ─────────────────────────────────────────────
    // Register / Unregister
    // ─────────────────────────────────────────────

    fun register() {
        val filter = IntentFilter(ChatAccessibilityService.ACTION_CHAT_CONTEXT)
        context.registerReceiver(chatContextReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "ChatAiSuggestionHelper registered")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(chatContextReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // Request AI Suggestions — call this from your keyboard toolbar button
    // ─────────────────────────────────────────────

    fun requestSuggestions(currentInput: String = "") {
        val messages = lastMessages
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "No chat context available yet")
            onSuggestionsReady(listOf("No chat context captured yet."))
            return
        }

        scope.launch {
            try {
                val prompt = buildPrompt(messages, currentInput)
                val suggestions = when (provider) {
                    AiProvider.GEMINI -> callGeminiApi(prompt)
                    AiProvider.OPENAI -> callOpenAiApi(prompt, OPENAI_BASE, OPENAI_MODEL)
                    AiProvider.GROQ -> callOpenAiApi(
                        prompt,
                        baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                        model = "llama3-8b-8192"
                    )
                }
                onSuggestionsReady(suggestions)
            } catch (e: Exception) {
                Log.e(TAG, "AI API error: ${e.message}")
                onSuggestionsReady(listOf("Error: ${e.message}"))
            }
        }
    }

    // ─────────────────────────────────────────────
    // Prompt Builder
    // ─────────────────────────────────────────────

    private fun buildPrompt(messages: Array<String>, currentInput: String): String {
        val conversation = messages.takeLast(10).joinToString("\n")
        val inputContext = if (currentInput.isNotBlank())
            "\nUser is currently typing: \"$currentInput\""
        else ""

        return """
            You are a smart reply assistant for a keyboard app.

            Here is a recent chat conversation:
            ---
            $conversation
            ---
            $inputContext

            Based on this conversation, suggest exactly 3 short, natural reply options for the user labeled as "ME".
            Rules:
            - Each reply must be under 15 words
            - Be conversational and contextually appropriate
            - Return ONLY a JSON array of 3 strings, no explanation
            - Example: ["Sure, sounds good!", "Let me check and get back to you", "That works for me!"]
        """.trimIndent()
    }

    // ─────────────────────────────────────────────
    // Gemini API Call
    // ─────────────────────────────────────────────

    private fun callGeminiApi(prompt: String): List<String> {
        val url = URL("$GEMINI_BASE/$GEMINI_MODEL:generateContent?key=$apiKey")
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 200)
            })
        }

        val responseText = postRequest(url, requestBody.toString(), authHeader = null)
        return parseGeminiResponse(responseText)
    }

    private fun parseGeminiResponse(response: String): List<String> {
        return try {
            val json = JSONObject(response)
            val text = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            parseJsonArrayFromText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini parse error: ${e.message}")
            listOf("Couldn't parse AI response")
        }
    }

    // ─────────────────────────────────────────────
    // OpenAI-Compatible API Call (works for OpenAI, Groq, OpenRouter)
    // ─────────────────────────────────────────────

    private fun callOpenAiApi(prompt: String, baseUrl: String, model: String): List<String> {
        val url = URL(baseUrl)
        val requestBody = JSONObject().apply {
            put("model", model)
            put("temperature", 0.7)
            put("max_tokens", 200)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a smart reply assistant for a keyboard app.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val responseText = postRequest(url, requestBody.toString(), "Bearer $apiKey")
        return parseOpenAiResponse(responseText)
    }

    private fun parseOpenAiResponse(response: String): List<String> {
        return try {
            val json = JSONObject(response)
            val text = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            parseJsonArrayFromText(text)
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI parse error: ${e.message}")
            listOf("Couldn't parse AI response")
        }
    }

    // ─────────────────────────────────────────────
    // HTTP Helper
    // ─────────────────────────────────────────────

    private fun postRequest(url: URL, body: String, authHeader: String?): String {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            authHeader?.let { conn.setRequestProperty("Authorization", it) }
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            if (conn.responseCode !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                throw Exception("HTTP ${conn.responseCode}: $error")
            }

            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────────────────────────────
    // JSON Array Parser
    // ─────────────────────────────────────────────

    private fun parseJsonArrayFromText(text: String): List<String> {
        // Find JSON array in response (handles cases where model adds extra text)
        val jsonStr = Regex("\\[.*?]", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.value ?: return listOf(text)

        return try {
            val arr = JSONArray(jsonStr)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            listOf(text)
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════
// USAGE EXAMPLE — In your InputMethodService or keyboard ViewModel:
// ═══════════════════════════════════════════════════════════════════════
//
// class HeliboardService : InputMethodService() {
//
//     private lateinit var aiHelper: ChatAiSuggestionHelper
//
//     override fun onCreate() {
//         super.onCreate()
//         val apiKey = PreferenceManager.getDefaultSharedPreferences(this)
//             .getString("gemini_api_key", "") ?: ""
//
//         aiHelper = ChatAiSuggestionHelper(
//             context = this,
//             apiKey = apiKey,
//             provider = ChatAiSuggestionHelper.AiProvider.GEMINI
//         ) { suggestions ->
//             // Run on main thread to update UI
//             runOnUiThread {
//                 updateSuggestionStrip(suggestions)
//             }
//         }
//         aiHelper.register()
//     }
//
//     // Call this when user taps "AI Suggest" toolbar button
//     fun onAiSuggestTapped() {
//         val currentInput = currentInputConnection?.getTextBeforeCursor(50, 0)?.toString() ?: ""
//         aiHelper.requestSuggestions(currentInput)
//     }
//
//     override fun onDestroy() {
//         super.onDestroy()
//         aiHelper.unregister()
//     }
// }
