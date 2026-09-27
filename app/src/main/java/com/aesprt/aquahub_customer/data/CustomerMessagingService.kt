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
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class CustomerMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        saveDeviceToken(this, token, uid)
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
            message.data["orderId"]?.let { putExtra("orderId", it) }
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
            val uid = explicitUid ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token.isNotBlank()) saveDeviceToken(context, token, uid)
            }
        }

        private fun saveDeviceToken(context: Context, token: String, uid: String) {
            val app = FirebaseApp.getApps(context).firstOrNull() ?: return
            FirebaseFirestore.getInstance(app, FIRESTORE_DATABASE)
                .collection("users").document(uid)
                .collection("devices").document(token.takeLast(64))
                .set(mapOf("token" to token, "platform" to "ANDROID", "updatedAt" to System.currentTimeMillis()))
        }
    }
}
