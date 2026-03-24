/*
 * ChatAccessibilityService.kt — MINIMAL (On-Demand Architecture)
 *
 * ONLY tracks which app is currently in the foreground.
 * All message extraction is done on-demand in OnDemandMessageExtractor.
 *
 * KEY METHOD: getRootForPackage(pkg)
 *   Uses getWindows() to find the correct app window even when keyboard is open.
 *   rootInActiveWindow returns the KEYBOARD window — we need the chat app window.
 */
package helium314.keyboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChatAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChatA11yService"

        @Volatile
        var instance: ChatAccessibilityService? = null
            private set

        // Supported chat apps — only these packages are tracked
        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android"
        )
    }

    /** Package of the foreground chat app. Only updated for WhatsApp/Instagram. */
    var currentPackage: String = ""
        private set

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0
        }
        Log.i(TAG, "ChatAccessibilityService connected — on-demand mode")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // ONLY store WhatsApp/Instagram — ignore keyboard, systemui, launcher, etc.
            if (pkg in SUPPORTED_PACKAGES) {
                currentPackage = pkg
                Log.d(TAG, "📱 currentPackage = $pkg")
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Find accessibility tree root for a specific package from ALL windows.
     *
     * WHY THIS EXISTS:
     *   rootInActiveWindow = keyboard window when keyboard is open.
     *   WhatsApp/Instagram window is BEHIND the keyboard.
     *   getWindows() returns ALL interactive windows including background ones.
     *   FLAG_RETRIEVE_INTERACTIVE_WINDOWS must be set (it is, in onServiceConnected).
     */
    fun getRootForPackage(targetPkg: String): AccessibilityNodeInfo? {
        return try {
            val allWindows = windows
            if (allWindows == null) {
                Log.w(TAG, "getWindows() returned null — falling back to rootInActiveWindow")
                return rootInActiveWindow
            }

            Log.d(TAG, "🔍 Searching ${allWindows.size} windows for pkg=$targetPkg")

            for (window in allWindows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: ""
                Log.d(TAG, "  window type=${window.type} pkg=$pkg")

                if (pkg == targetPkg) {
                    Log.d(TAG, "✅ Found $targetPkg window (type=${window.type})")
                    return root
                }
                root.recycle()
            }

            Log.w(TAG, "⚠️ $targetPkg window not found — falling back to rootInActiveWindow")
            rootInActiveWindow

        } catch (e: Exception) {
            Log.e(TAG, "getRootForPackage error: ${e.message}")
            rootInActiveWindow
        }
    }

    /** Legacy — kept for compat */
    fun readCurrentScreen(): AccessibilityNodeInfo? = rootInActiveWindow
}
