package com.aesprt.aquahub_customer

import android.app.Application
import com.aesprt.aquahub_customer.data.CustomerMessagingService
import com.aesprt.aquahub_customer.di.customerModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AquaHubCustomerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CustomerMessagingService.createNotificationChannel(this)
        startKoin {
            androidContext(this@AquaHubCustomerApplication)
            modules(customerModule)
        }
    }
}
