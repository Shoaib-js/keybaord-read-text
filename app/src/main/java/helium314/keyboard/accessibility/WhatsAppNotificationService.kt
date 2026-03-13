package helium314.keyboard.accessibility

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * WhatsAppNotificationService — Notification trigger layer
 *
 * ═══════════════════════════════════════════════════════════════
 * PURPOSE:
 *   This service listens for incoming WhatsApp / Instagram
 *   notifications and broadcasts a trigger event to
 *   ChatAccessibilityService, which then performs a forced
 *   accessibility rescan to capture the new message.
 *
 * WHY THIS IS NEEDED:
 *   AccessibilityService fires on UI events (scroll, window change).
 *   But when a message arrives while the chat is already open
 *   and the user is typing, no scroll/window event fires.
 *   NotificationListenerService catches EVERY new message arrival
 *   — even when the app is in foreground with keyboard open.
 *
 * FLOW:
 *   New WhatsApp message
 *       ↓
 *   onNotificationPosted()
 *       ↓
 *   extract: sender, text, timestamp
 *       ↓
 *   sendBroadcast(ACTION_NEW_CHAT_MESSAGE)
 *       ↓
 *   ChatAccessibilityService receives broadcast
 *       ↓
 *   forceRescan() → WhatsAppExtractor → TreeMap → AI broadcast
 *
 * EDGE CASES HANDLED:
 *   ✓ Group summary notifications  → skipped (FLAG_GROUP_SUMMARY)
 *   ✓ Ongoing notifications        → skipped (FLAG_ONGOING_EVENT)
 *   ✓ Empty / null text            → skipped
 *   ✓ Media / file notifications   → skipped (no usable text)
 *   ✓ Multiple-message summary     → skipped (no specific sender)
 *   ✓ WhatsApp Business            → supported (com.whatsapp.w4b)
 *   ✓ Instagram DMs                → supported (com.instagram.android)
 * ═══════════════════════════════════════════════════════════════
 */
class WhatsAppNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "NOTIF_DEBUG"

        // Broadcast action — ChatAccessibilityService listens for this
        const val ACTION_NEW_CHAT_MESSAGE = "helium314.keyboard.NEW_CHAT_MESSAGE"

        // Extras sent with the broadcast
        const val EXTRA_NOTIF_PACKAGE   = "notif_package"    // e.g. "com.whatsapp"
        const val EXTRA_NOTIF_SENDER    = "notif_sender"     // e.g. "Rahul Bhai"
        const val EXTRA_NOTIF_MESSAGE   = "notif_message"    // e.g. "Kal miloge?"
        const val EXTRA_NOTIF_TIMESTAMP = "notif_timestamp"  // System.currentTimeMillis()

        // Packages we care about
        private val WATCHED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android"
        )

        // Notification texts to ignore — UI chrome / system strings
        private val IGNORE_TEXTS = setOf(
            "messages", "new messages", "missed call",
            "missed voice call", "missed video call",
            "you have a missed call",
            "tap to call back",
            "photo", "video", "audio", "document", "sticker", "gif",
            "voice message", "contact card",
            "this message was deleted",
            "you deleted this message"
        )
    }

    // ══════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "╔══════════════════════════════════════════╗")
        Log.i(TAG, "║  WhatsAppNotificationService CONNECTED   ║")
        Log.i(TAG, "║  Watching: WhatsApp + Instagram          ║")
        Log.i(TAG, "╚══════════════════════════════════════════╝")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "WhatsAppNotificationService DISCONNECTED")
    }

    // ══════════════════════════════════════════════════════════════════════
    // MAIN CALLBACK — called for every new notification
    // ══════════════════════════════════════════════════════════════════════
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val pkg = sbn.packageName ?: return
        if (pkg !in WATCHED_PACKAGES) return

        val notification = sbn.notification ?: return

        // ── Edge Case 1: Skip group summary notifications ────────────────
        // WhatsApp uses FLAG_GROUP_SUMMARY for "5 new messages" rollup.
        // These don't have individual message text — skip them.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            Log.v(TAG, "⏭ Skip group summary — pkg=$pkg")
            return
        }

        // ── Edge Case 2: Skip ongoing notifications ──────────────────────
        // Ongoing = calls, media playback — not chat messages.
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            Log.v(TAG, "⏭ Skip ongoing notification — pkg=$pkg")
            return
        }

        val extras: Bundle = notification.extras ?: return

        // ── Extract sender and message text ──────────────────────────────
        // android.title = sender name (contact name or group name)
        // android.text  = message content (single message)
        // android.bigText = full text for expanded notifications
        val sender  = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val msgText = (
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            )

        // ── Edge Case 3: Skip if sender or text is missing ───────────────
        if (sender.isNullOrEmpty()) {
            Log.v(TAG, "⏭ Skip: no sender — pkg=$pkg")
            return
        }
        if (msgText.isNullOrEmpty()) {
            Log.v(TAG, "⏭ Skip: no message text — pkg=$pkg sender=$sender")
            return
        }

        // ── Edge Case 4: Skip system / media texts ───────────────────────
        // Match against ignore list (case-insensitive)
        val msgLower = msgText.lowercase().trim()
        if (msgLower in IGNORE_TEXTS) {
            Log.v(TAG, "⏭ Skip: ignore list — text=[$msgText]")
            return
        }

        // ── Edge Case 5: Skip multi-message summary ───────────────────────
        // WhatsApp sometimes sends "5 messages from 3 chats" style text.
        // These match patterns like "X messages" or contain "chats".
        if (msgLower.matches(Regex("\\d+ (new )?messages?.*")) ||
            msgLower.contains("chats")) {
            Log.v(TAG, "⏭ Skip: multi-message summary — text=[$msgText]")
            return
        }

        // ── Edge Case 6: Skip "typing…" indicators ────────────────────────
        if (msgLower == "typing…" || msgLower == "typing") {
            Log.v(TAG, "⏭ Skip: typing indicator")
            return
        }

        // ── Notification is valid — extract message from OTHER person ─────
        // Note: Notifications only show messages from OTHER people.
        // Messages sent by "me" never appear as notifications.
        val timestamp = sbn.postTime   // milliseconds — most accurate timestamp

        Log.i(TAG, "🔔 New message | pkg=$pkg | sender=[$sender] | text=[$msgText]")

        // ── Broadcast trigger to ChatAccessibilityService ─────────────────
        sendBroadcast(Intent(ACTION_NEW_CHAT_MESSAGE).apply {
            setPackage(applicationContext.packageName)   // internal only
            putExtra(EXTRA_NOTIF_PACKAGE,   pkg)
            putExtra(EXTRA_NOTIF_SENDER,    sender)
            putExtra(EXTRA_NOTIF_MESSAGE,   msgText)
            putExtra(EXTRA_NOTIF_TIMESTAMP, timestamp)
        })

        Log.d(TAG, "📡 Broadcast sent → ACTION_NEW_CHAT_MESSAGE")
    }

    // ── We don't need onNotificationRemoved for this feature ─────────────
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
