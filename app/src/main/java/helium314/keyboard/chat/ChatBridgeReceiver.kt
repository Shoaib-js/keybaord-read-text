// ChatBridgeReceiver — EMPTY STUB (deprecated in on-demand architecture)
// All message reading is now done on-demand in OnDemandMessageExtractor.
// This file is kept so the build does not fail on any remaining references.
package helium314.keyboard.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ChatBridgeReceiver : BroadcastReceiver() {
    fun register(context: Context) {}
    fun unregister(context: Context) {}
    override fun onReceive(context: Context?, intent: Intent?) {}
}
