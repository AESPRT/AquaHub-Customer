package com.aesprt.aquahub_customer

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aesprt.aquahub_customer.ui.CustomerApp
import com.aesprt.aquahub_customer.ui.theme.AquaHubCustomerTheme
import com.aesprt.aquahub_customer.data.preferences.ThemePreferences
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val themePreferences: ThemePreferences by inject()
    private var incomingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingLink = intent.dataString
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreferences.mode.collectAsState(initial = com.aesprt.aquahub_customer.data.preferences.ThemeMode.SYSTEM)
            AquaHubCustomerTheme(themeMode = themeMode, dynamicColor = false) {
                CustomerApp(
                    incomingLink = incomingLink,
                    onIncomingLinkConsumed = { incomingLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink = intent.dataString
    }
}
