package com.aesprt.aquahub_customer.data

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService

class CustomerMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val app = FirebaseApp.getApps(this).firstOrNull() ?: return
        FirebaseFirestore.getInstance(app, FIRESTORE_DATABASE)
            .collection("users").document(uid)
            .collection("devices").document(token.takeLast(32))
            .set(mapOf("token" to token, "platform" to "ANDROID", "updatedAt" to System.currentTimeMillis()))
    }
}
