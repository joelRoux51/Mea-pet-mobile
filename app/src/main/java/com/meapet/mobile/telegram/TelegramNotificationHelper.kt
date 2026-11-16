package com.meapet.mobile.telegram

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meapet.mobile.R
import com.meapet.mobile.core.LocaleHelper
import com.meapet.mobile.core.getStringOrFallback
import com.meapet.mobile.ui.MainActivity

/**
 * Displays Android push notifications for incoming Telegram bot messages.
 */
class TelegramNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "telegram_messages"
        private const val BASE_NOTIFICATION_ID = 2000
    }

    private var seq = 0

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val localized = LocaleHelper.wrap(context.applicationContext)
            val name = localized.getStringOrFallback(R.string.telegram_notif_channel_name, "Telegram Messages")
            val desc = localized.getStringOrFallback(
                R.string.telegram_notif_channel_desc,
                "Push notifications received from your Telegram bot"
            )
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = desc
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    fun showNotification(sender: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()
        val localized = LocaleHelper.wrap(context.applicationContext)
        val title = sender.ifBlank {
            localized.getStringOrFallback(R.string.telegram_notif_default_sender, "Telegram Bot")
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(localized, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val id = BASE_NOTIFICATION_ID + ((++seq) % 50)
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked concurrently
        }
    }
}
