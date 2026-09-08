package com.aesprt.aquahub_customer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aesprt.aquahub_customer.ui.CustomerApp
import com.aesprt.aquahub_customer.ui.theme.AquaHubCustomerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AquaHubCustomerTheme(dynamicColor = false) { CustomerApp() }
        }
    }
}
