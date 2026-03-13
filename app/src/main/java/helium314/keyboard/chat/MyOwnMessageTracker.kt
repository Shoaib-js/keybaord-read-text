package helium314.keyboard.chat

import android.util.Log

/**
 * MyOwnMessageTracker
 *
 * Captures messages typed and sent BY THE USER (Sender.ME) from within
 * the keyboard itself. Since LeanType IS the keyboard, we can intercept
 * text exactly at the "send" moment — no accessibility or notification
 * permission needed for this path.
 *
 * ─────────────────────────────────────────────────────────────────
 * WHERE TO CALL THIS — InputLogic.java → performEditorAction()
 * ─────────────────────────────────────────────────────────────────
 *
 * Find the existing method (around line 2530):
 *
 *   private void performEditorAction(final int actionId) {
 *       mConnection.performEditorAction(actionId);
 *   }
 *
 * Replace it with:
 *
 *   private void performEditorAction(final int actionId) {
 *       // ── MY OWN MESSAGE TRACKER ──────────────────────────────────────
 *       if (actionId == EditorInfo.IME_ACTION_SEND ||
 *           actionId == EditorInfo.IME_ACTION_GO   ||
 *           actionId == EditorInfo.IME_ACTION_DONE) {
 *           CharSequence before = mConnection.getTextBeforeCursor(4096, 0);
 *           MyOwnMessageTracker.onUserSentMessage(before);
 *       }
 *       // ── END TRACKER ─────────────────────────────────────────────────
 *       mConnection.performEditorAction(actionId);
 *   }
 *
 * Also add the import at the top of InputLogic.java (around line 28):
 *   import helium314.keyboard.chat.MyOwnMessageTracker;
 *
 * ─────────────────────────────────────────────────────────────────
 * WHY IME_ACTION_DONE is included:
 *   Some apps (notably WhatsApp voice/media) use IME_ACTION_DONE
 *   as their send trigger. Including it ensures we never miss a send.
 *   The guard [MessageCache.shared.currentChatKey == null] prevents
 *   spurious captures in non-chat fields.
 * ─────────────────────────────────────────────────────────────────
 */
object MyOwnMessageTracker {

    private const val TAG = "OwnMsgTracker"

    /**
     * Called from Java — accepts CharSequence (getTextBeforeCursor return type).
     * Passes through to the String overload.
     */
    @JvmStatic
    fun onUserSentMessage(text: CharSequence?) {
        onUserSentMessage(text?.toString() ?: return)
    }

    /**
     * Primary implementation.
     * Skips tracking if:
     *   • [text] is blank (nothing typed)
     *   • No chat is active ([MessageCache.shared.currentChatKey] == null),
     *     meaning the user is not in a WhatsApp/Instagram conversation.
     */
    @JvmStatic
    fun onUserSentMessage(text: String) {
        val trimmed = text.trim()

        if (trimmed.isBlank()) {
            Log.v(TAG, "Skipped: blank text")
            return
        }

        if (MessageCache.shared.currentChatKey == null) {
            Log.v(TAG, "Skipped: no active chat")
            return
        }

        val message = ChatMessage(
            text   = trimmed,
            sender = ChatMessage.Sender.ME
        )

        val added = MessageCache.shared.add(message)
        Log.d(TAG, "onUserSentMessage added=$added: ${trimmed.take(60)}")
    }
}
