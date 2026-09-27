package com.aesprt.aquahub_customer.data

import com.aesprt.aquahub_customer.BuildConfig

/** Official links are supplied per build from local.properties or CI secrets. */
object AquaHubLinks {
    val privacyPolicy: String? = BuildConfig.PRIVACY_POLICY_URL.takeIf(String::isNotBlank)
    val terms: String? = BuildConfig.TERMS_URL.takeIf(String::isNotBlank)
    val support: String? = BuildConfig.SUPPORT_URL.takeIf(String::isNotBlank)
}
