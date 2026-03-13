package helium314.keyboard.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AiSuggestionEngine
 *
 * Reads [MessageCache.shared], formats a prompt, and delegates to
 * [AiApiClient] to fetch AI-generated reply suggestions.
 *
 * ─── Previously broken ──────────────────────────────────────────
 * This file referenced [AiApiClient] which did not exist.
 * Build error:
 *   "Unresolved reference: AiApiClient"   ← fixed by adding AiApiClient.kt
 *   "Unresolved reference: complete"      ← fixed by same file
 * ─────────────────────────────────────────────────────────────────
 *
 * ─── Usage in LatinIME (or any coroutine scope) ─────────────────
 *
 *   // Create once (e.g. lazy field in LatinIME):
 *   private val aiEngine by lazy {
 *       AiSuggestionEngine(
 *           BackendAiApiClient(
 *               backendUrl = "https://yourserver.com/generate-reply",
 *               app  = "whatsapp",
 *               tone = selectedTone          // from AiTonePanel
 *           )
 *       )
 *   }
 *
 *   // When user taps AI + tone:
 *   scope.launch {
 *       val suggestions = aiEngine.getSuggestions()
 *       withContext(Dispatchers.Main) { showAiSuggestions(suggestions) }
 *   }
 *
 * ─────────────────────────────────────────────────────────────────
 */
class AiSuggestionEngine(private val apiClient: AiApiClient) {

    companion object {
        private const val TAG = "AiEngine"
    }

    /**
     * Build a prompt from the current [MessageCache.shared] content and
     * return AI reply suggestions.
     *
     * Always runs on [Dispatchers.IO] — safe to call from any coroutine scope.
     * Returns an empty list if the cache is empty or the API call fails.
     */
    suspend fun getSuggestions(): List<String> = withContext(Dispatchers.IO) {
        val messages = MessageCache.shared.getAll()

        if (messages.isEmpty()) {
            Log.w(TAG, "Cache is empty — no suggestions generated")
            return@withContext emptyList()
        }

        Log.d(TAG, "Building prompt from ${messages.size} cached messages")
        val prompt = buildPrompt(messages)

        Log.d(TAG, "Sending prompt to AI (${prompt.length} chars)")
        val suggestions = apiClient.complete(prompt)

        Log.d(TAG, "Received ${suggestions.size} suggestions")
        suggestions
    }

    // ─────────────────────────────────────────────────────────────
    // PROMPT BUILDER
    //
    // Format:
    //   Them: Kal miloge?
    //   Me:   Haan zaroor
    //   Them: Kahan milna hai?
    //
    // Then instructions for the AI.
    // ─────────────────────────────────────────────────────────────
    private fun buildPrompt(messages: List<ChatMessage>): String {
        val history = messages.joinToString("\n") { msg ->
            val label = if (msg.sender == ChatMessage.Sender.ME) "Me" else "Them"
            "$label: ${msg.text}"
        }

        return """
You are a smart keyboard assistant helping the user reply to a chat message.

Conversation (oldest → newest):
$history

Task: Suggest exactly 3 short, natural reply options the user ("Me") can send next.

Rules:
- One suggestion per line, no numbering, no bullets
- Each reply should be under 20 words
- Match the tone and language of the conversation
- Write as if you are "Me" replying to "Them"
        """.trimIndent()
    }
}
