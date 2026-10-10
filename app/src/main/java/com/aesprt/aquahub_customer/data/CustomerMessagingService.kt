package com.aesprt.aquahub_customer.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aesprt.aquahub_customer.MainActivity
import com.aesprt.aquahub_customer.data.supabase.SupabaseDeviceTokenRepository
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CustomerMessagingService : FirebaseMessagingService() {
    private val tokenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        tokenScope.launch { SupabaseDeviceTokenRepository.upsert(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        createNotificationChannel(applicationContext)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "AquaHub order update"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "Your AquaHub order has a new update."
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            (message.data["order_id"] ?: message.data["orderId"])?.let { putExtra("orderId", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            (System.currentTimeMillis() % 100000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID_ORDER_UPDATES)
            .setSmallIcon(com.aesprt.aquahub_customer.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext)
                .notify((System.currentTimeMillis() % 100000).toInt(), notification)
        }
    }

    companion object {
        const val CHANNEL_ID_ORDER_UPDATES = "aquahub_order_updates"
        private const val CHANNEL_NAME_ORDER_UPDATES = "Order updates"

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID_ORDER_UPDATES,
                CHANNEL_NAME_ORDER_UPDATES,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications about AquaHub customer orders"
                enableLights(true)
                enableVibration(true)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }

        fun registerCurrentDeviceToken(context: Context, explicitUid: String? = null) {
            if (explicitUid != null) return // legacy parameter retained for source compatibility only
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token.isNotBlank()) {
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        SupabaseDeviceTokenRepository.upsert(token)
                    }
                }
            }
        }
    }
}
