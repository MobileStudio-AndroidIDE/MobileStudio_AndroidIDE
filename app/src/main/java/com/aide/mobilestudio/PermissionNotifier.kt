package com.aide.mobilestudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Handles permission-related notifications with vibration patterns.
 * 
 * Vibration pattern for "Permission Required":
 *   50ms on, 50ms off, 50ms on, 50ms off, 100ms on
 *   = "징 · 징 · 지이잉"
 */
object PermissionNotifier {
    
    private const val CHANNEL_ID = "permission_notifications"
    private const val NOTIFICATION_ID = 9001
    private const val PREFS_NAME = "permission_notifier_prefs"
    private const val KEY_ENABLED = "vibration_enabled"
    
    /** Default vibration pattern: 50ms on, 50ms off, 50ms on, 50ms off, 100ms on */
    private val VIBRATION_PATTERN = longArrayOf(0, 50, 50, 50, 50, 100)
    
    /**
     * Shows a permission required notification with the configured vibration pattern.
     * 
     * @param context Application context
     * @param toolName Name of the tool requesting permission (e.g., "opencode")
     * @param permissionDescription Human-readable description of what permission is needed
     * @param onAllowOnce Callback when user selects "Allow once"
     * @param onAllowAlways Callback when user selects "Allow always"
     * @param onReject Callback when user selects "Reject"
     */
    fun showPermissionRequest(
        context: Context,
        toolName: String,
        permissionDescription: String,
        onAllowOnce: () -> Unit,
        onAllowAlways: () -> Unit,
        onReject: () -> Unit
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val vibrationEnabled = prefs.getBoolean(KEY_ENABLED, true)
        
        if (vibrationEnabled) {
            vibrate(context)
        }
        
        ensureChannel(context)
        
        val allowOnceIntent = createActionIntent(context, "ALLOW_ONCE", toolName)
        val allowAlwaysIntent = createActionIntent(context, "ALLOW_ALWAYS", toolName)
        val rejectIntent = createActionIntent(context, "REJECT", toolName)
        
        val allowOnceAction = PendingIntent.getBroadcast(
            context, 0, allowOnceIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val allowAlwaysAction = PendingIntent.getBroadcast(
            context, 1, allowAlwaysIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val rejectAction = PendingIntent.getBroadcast(
            context, 2, rejectIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Permission Required")
            .setContentText("$toolName: $permissionDescription")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$toolName requests permission:\n$permissionDescription\n\nHow would you like to proceed?"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_agenda, "Allow Once", allowOnceAction).build())
            .addAction(NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_save, "Allow Always", allowAlwaysAction).build())
            .addAction(NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectAction).build())
        
        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.notify(NOTIFICATION_ID, builder.build())
        
        // Store callbacks for the broadcast receiver
        PermissionActionReceiver.storeCallbacks(toolName, onAllowOnce, onAllowAlways, onReject)
    }
    
    /**
     * Checks if vibration is enabled in settings.
     */
    fun isVibrationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }
    
    /**
     * Enables or disables vibration for permission notifications.
     */
    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    
    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATION_PATTERN, -1)
            }
        }
    }
    
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Permission Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for permission requests from CLI tools"
                    enableVibration(true)
                    vibrationPattern = VIBRATION_PATTERN
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
    
    private fun createActionIntent(context: Context, action: String, toolName: String): android.content.Intent {
        return android.content.Intent(context, PermissionActionReceiver::class.java).apply {
            this.action = action
            putExtra("tool_name", toolName)
        }
    }
}

/**
 * Broadcast receiver to handle permission notification actions.
 * This allows the notification buttons to work even when the app is in background.
 */
class PermissionActionReceiver : android.content.BroadcastReceiver() {
    
    companion object {
        private val callbacks = mutableMapOf<String, Triple<() -> Unit, () -> Unit, () -> Unit>>()
        
        fun storeCallbacks(toolName: String, onAllowOnce: () -> Unit, onAllowAlways: () -> Unit, onReject: () -> Unit) {
            callbacks[toolName] = Triple(onAllowOnce, onAllowAlways, onReject)
        }
        
        private fun getAndRemoveCallbacks(toolName: String): Triple<() -> Unit, () -> Unit, () -> Unit>? {
            return callbacks.remove(toolName)
        }
    }
    
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        val action = intent.action ?: return
        val toolName = intent.getStringExtra("tool_name") ?: return
        
        val callbacks = getAndRemoveCallbacks(toolName) ?: return
        
        when (action) {
            "ALLOW_ONCE" -> callbacks.first()
            "ALLOW_ALWAYS" -> callbacks.second()
            "REJECT" -> callbacks.third()
        }
    }
}