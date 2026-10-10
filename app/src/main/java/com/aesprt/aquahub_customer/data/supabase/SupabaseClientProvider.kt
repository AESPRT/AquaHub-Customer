package com.aesprt.aquahub_customer.data.supabase

import com.aesprt.aquahub_customer.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.android.Android
import kotlin.time.Duration.Companion.seconds

object SupabaseClientProvider {
    val client by lazy {
        require(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
            "Supabase is not configured for this build."
        }
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
            httpEngine = Android.create()
            requestTimeout = 30.seconds
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                flowType = FlowType.PKCE
                scheme = "aquahub"
                host = "auth"
            }
            install(Postgrest)
            install(Functions)
        }
    }
}
