package com.aesprt.aquahub_customer.ui.feature.auth

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub_customer.R
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.AquaHubGlassCard
import com.aesprt.aquahub_customer.ui.components.AquaHubLogo
import com.aesprt.aquahub_customer.ui.components.AquaPrimaryButton
import com.aesprt.aquahub_customer.ui.theme.AquaHubCustomerTheme

private enum class AuthMode { SIGN_IN, CREATE_ACCOUNT }

@Composable
fun AuthScreen(
    firebaseConfigured: Boolean,
    viewModel: CustomerViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    AuthContent(
        firebaseConfigured = firebaseConfigured,
        authLoading = state.authLoading,
        authError = state.authError,
        onSignIn = viewModel::signInWithEmail,
        onRegister = viewModel::registerWithEmail,
        onResetPassword = viewModel::resetPassword,
        onGoogleSignIn = viewModel::signInWithGoogle,
        onClearAuthError = viewModel::clearAuthError,
        modifier = modifier,
    )
}

@Composable
private fun AuthContent(
    firebaseConfigured: Boolean,
    authLoading: Boolean,
    authError: String?,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String, String) -> Unit,
    onResetPassword: (String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onClearAuthError: () -> Unit,
    modifier: Modifier = Modifier,
    initialMode: AuthMode = AuthMode.SIGN_IN,
) {
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var forgotPassword by remember { mutableStateOf(false) }
    val appName = stringResource(R.string.app_name)

    if (forgotPassword) {
        AlertDialog(
            onDismissRequest = { forgotPassword = false },
            title = { Text("Reset your password") },
            text = {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    leadingIcon = { Icon(Icons.Outlined.Email, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forgotPassword = false; onResetPassword(email)
                }) { Text("Send email") }
            },
            dismissButton = { TextButton(onClick = { forgotPassword = false }) { Text("Cancel") } }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(132.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = CircleShape,
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = .35f),
                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = .35f),
                        )
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                            shape = CircleShape,
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = .38f),
                                    MaterialTheme.colorScheme.secondary.copy(alpha = .18f),
                                ),
                            ),
                            shape = CircleShape,
                        )
                        .padding(24.dp),
                ) {
                    AquaHubLogo(
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = appName,
                    )
                }
        }
        Text(
            "Pure, fresh water\nat your doorstep.",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            "Order from trusted local stations and follow every delivery step.",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .86f),
            style = MaterialTheme.typography.bodyLarge
        )

        AquaHubGlassCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .88f),
            borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .22f),
            elevation = 2.dp
        ) {
            Text(
                if (mode == AuthMode.SIGN_IN) "Welcome back".uppercase() else "Create your account".uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            if (mode == AuthMode.CREATE_ACCOUNT) {
                OutlinedTextField(
                    name,
                    { name = it },
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    label = { Text("Full name") },
                    leadingIcon = { Icon(Icons.Outlined.Person, null) },
                    singleLine = true
                )
            }
            OutlinedTextField(
                email,
                { email = it },
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                label = { Text("Email") },
                leadingIcon = { Icon(Icons.Outlined.Email, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            OutlinedTextField(
                password,
                { password = it },
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            if (mode == AuthMode.CREATE_ACCOUNT) {
                OutlinedTextField(
                    confirmPassword,
                    { confirmPassword = it },
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    label = { Text("Confirm password") },
                    leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                    trailingIcon = {
                        IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                            Icon(
                                imageVector = if (confirmPasswordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password",
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
            authError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    textAlign = TextAlign.Center,
                )
            }
            if (mode == AuthMode.SIGN_IN) TextButton(
                onClick = { forgotPassword = true },
                modifier = Modifier.align(Alignment.End)
            ) { Text("Forgot password?") }
            Spacer(Modifier.height(12.dp))
            AquaPrimaryButton(
                text = if (mode == AuthMode.SIGN_IN) "Sign in" else "Create account",
                onClick = {
                    if (mode == AuthMode.SIGN_IN) onSignIn(email, password)
                    else onRegister(name, email, password, confirmPassword)
                },
                loading = authLoading,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onGoogleSignIn,
                enabled = firebaseConfigured && !authLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .padding(top = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .32f)
                )
            ) {
                Image(
                    painter = painterResource(R.drawable.google_g_logo),
                    contentDescription = "Google",
                    modifier = Modifier.size(20.dp)
                )
                Text("Continue with Google", modifier = Modifier.padding(start = 10.dp))
            }
            if (!firebaseConfigured) Text(
                "Firebase is not configured, so sign-in and live ordering are unavailable.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (mode == AuthMode.SIGN_IN) "Don\u0027t have an account?" else "Already have an account?",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .9f),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(
                onClick = {
                    mode = if (mode == AuthMode.SIGN_IN) AuthMode.CREATE_ACCOUNT else AuthMode.SIGN_IN
                    onClearAuthError()
                },
                enabled = !authLoading
            ) {
                Text(if (mode == AuthMode.SIGN_IN) "Create account" else "Sign in")
            }
        }
    }
}

@Preview(name = "Sign in - Light", showBackground = true, showSystemUi = true)
@Preview(name = "Sign in - Dark", showBackground = true, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AuthScreenSignInPreview() {
    AquaHubCustomerTheme(dynamicColor = false) {
        AuthContent(
            firebaseConfigured = true,
            authLoading = false,
            authError = null,
            onSignIn = { _, _ -> },
            onRegister = { _, _, _, _ -> },
            onResetPassword = {},
            onGoogleSignIn = {},
            onClearAuthError = {},
        )
    }
}

@Preview(name = "Create account - Light", showBackground = true, showSystemUi = true)
@Preview(name = "Create account - Dark", showBackground = true, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AuthScreenCreateAccountPreview() {
    AquaHubCustomerTheme(dynamicColor = false) {
        AuthContent(
            firebaseConfigured = true,
            authLoading = false,
            authError = null,
            onSignIn = { _, _ -> },
            onRegister = { _, _, _, _ -> },
            onResetPassword = {},
            onGoogleSignIn = {},
            onClearAuthError = {},
            initialMode = AuthMode.CREATE_ACCOUNT,
        )
    }
}


@Composable
fun EmailVerificationScreen(viewModel: CustomerViewModel) {
    val state by viewModel.state.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AquaHubLogo(modifier = Modifier.height(88.dp), contentDescription = "AquaHub")
        Spacer(Modifier.height(24.dp))
        Text(
            "Verify your email to continue",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "We sent a verification link to ${state.profile?.email.orEmpty()}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
        AquaPrimaryButton(
            "I've verified my email",
            { viewModel.refreshEmailVerification() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        )
        TextButton(onClick = viewModel::resendEmailVerification) { Text("Resend verification email") }
        TextButton(onClick = viewModel::signOut) { Text("Use another account") }
    }
}
