package com.aesprt.aquahub_customer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.aesprt.aquahub_customer.R

@Composable
fun AquaHubLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = "AquaHub logo",
    contentScale: ContentScale = ContentScale.Fit,
) {
    Image(
        painter = painterResource(R.drawable.aquahub_customer_logo),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}
