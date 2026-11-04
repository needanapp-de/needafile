package de.needanapp.needafile.security

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.RowPosition
import de.needanapp.design.components.SegmentedRow
import de.needanapp.design.components.SignalButton
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.design.theme.LocalDesignColors
import de.needanapp.design.theme.designBackground
import de.needanapp.needafile.R
import de.needanapp.needafile.settings.AppIconImage
import de.needanapp.needafile.system.LauncherIcon
import java.util.WeakHashMap
import kotlinx.coroutines.delay

/** Something waiting for the user to prove who they are, shown by the screen of the activity that asked. */
private sealed interface SecurityRequest {
    class Verify(val kind: ProtectionKind, val onSuccess: () -> Unit) : SecurityRequest
    class SetUp(val onDone: (Protection) -> Unit) : SecurityRequest
}

private val requests = WeakHashMap<Activity, MutableState<SecurityRequest?>>()

private fun Activity.securityRequest() = requests.getOrPut(this) { mutableStateOf(null) }

/** Runs [action] right away, or once the user confirmed the protection of [kind] if it is switched on. */
fun Activity.withProtection(kind: ProtectionKind, action: () -> Unit) {
    if (Protections.get(this, kind) == null) action() else securityRequest().value = SecurityRequest.Verify(kind, action)
}

/** Lets the user choose pattern, PIN or fingerprint and enter it twice; [onDone] gets the new protection. */
fun Activity.setUpProtection(onDone: (Protection) -> Unit) {
    securityRequest().value = SecurityRequest.SetUp(onDone)
}

/** Shows what [withProtection] or [setUpProtection] asked for on this screen; part of every themed screen. */
@Composable
fun SecurityRequestHost() {
    val activity = LocalActivity.current ?: return
    val state = remember(activity) { activity.securityRequest() }
    val request = state.value ?: return
    val close = { state.value = null }

    when (request) {
        is SecurityRequest.Verify -> {
            val protection = remember(request) { Protections.get(activity, request.kind) }
            if (protection == null) {
                LaunchedEffect(request) {
                    close()
                    request.onSuccess()
                }
                return
            }
            OptionsSheet(onDismiss = close, skipPartiallyExpanded = true) {
                Column(Modifier.verticalScrollWithBar()) {
                    LockEntry(protection.type, expected = protection.sealed, onDone = {
                        close()
                        request.onSuccess()
                    })
                }
            }
        }

        is SecurityRequest.SetUp -> {
            // the fingerprint is offered wherever the device has a sensor, with a way to set one up in Android if needed
            val types = remember { LockType.entries.filter { it != LockType.BIOMETRIC || Protections.biometricState(activity) != BiometricState.UNAVAILABLE } }
            var type by remember { mutableStateOf(LockType.PIN) }
            OptionsSheet(onDismiss = close, title = stringResource(R.string.protection_set_up), skipPartiallyExpanded = true) {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    SegmentedRow(RowPosition.SINGLE, null, types, type, label = { stringResource(it.label) }, onSelect = { type = it })
                }
                // a new type starts from scratch
                Column(Modifier.verticalScrollWithBar()) {
                    key(type) {
                        LockEntry(type, expected = null, onDone = {
                            close()
                            request.onDone(it)
                        })
                    }
                }
            }
        }
    }
}

/**
 * Entering a pattern, PIN or fingerprint. With an [expected] hash it checks against it (counting wrong attempts),
 * without one it sets up a new protection: entered twice, [onDone] gets it.
 */
@Composable
private fun LockEntry(type: LockType, expected: String?, onDone: (Protection) -> Unit) {
    val activity = LocalActivity.current ?: return
    val isSetUp = expected == null
    // a fingerprint set up in Android meanwhile counts as soon as the user is back
    var biometrics by remember { mutableStateOf(Protections.biometricState(activity)) }
    if (type == LockType.BIOMETRIC) {
        LifecycleResumeEffect(Unit) {
            biometrics = Protections.biometricState(activity)
            onPauseOrDispose { }
        }
    }
    val defaultText = stringResource(
        when (type) {
            LockType.PIN -> R.string.enter_pin
            LockType.PATTERN -> R.string.insert_pattern
            LockType.BIOMETRIC -> if (biometrics == BiometricState.READY) R.string.authenticate else R.string.fingerprint_not_set_up
        },
    )
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    // while setting up, the first entry waits here (in memory only) for the repetition
    var firstSecret by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var patternKey by remember { mutableIntStateOf(0) }
    var patternWrong by remember { mutableStateOf(false) }
    var lockout by remember { mutableIntStateOf(if (isSetUp) 0 else Protections.remainingLockout(activity)) }

    // counts down the wait after too many wrong attempts
    LaunchedEffect(lockout > 0) {
        while (lockout > 0) {
            delay(1000)
            lockout = Protections.remainingLockout(activity)
        }
    }
    // a wrong entry shows its message for a moment, then the pad is ready again
    LaunchedEffect(isError, patternWrong) {
        if (isError || patternWrong) {
            delay(ERROR_DISPLAY_MS)
            if (patternWrong) {
                patternWrong = false
                patternKey++
            }
            isError = false
            message = if (isSetUp && firstSecret != null) message else null
        }
    }

    fun fail(text: String) {
        message = text
        isError = true
        if (type == LockType.PATTERN) patternWrong = true
    }

    fun entered(secret: String, isLongEnough: Boolean, tooShort: String, repeat: String, wrong: String) {
        when {
            isSetUp && firstSecret == null && !isLongEnough -> fail(tooShort)
            isSetUp && firstSecret == null -> {
                firstSecret = secret
                message = repeat
                if (type == LockType.PATTERN) patternKey++
            }

            isSetUp && secret == firstSecret -> onDone(Protection(type, Protections.seal(secret)))
            isSetUp -> {
                firstSecret = null
                fail(wrong)
            }

            expected != null && Protections.matches(secret, expected) -> {
                Protections.registerSuccess(activity)
                onDone(Protection(type, expected))
            }

            else -> {
                lockout = Protections.registerFailure(activity)
                if (lockout == 0) fail(wrong) else if (type == LockType.PATTERN) patternWrong = true
            }
        }
    }

    fun authenticate() {
        activity.authenticateBiometric(onSuccess = {
            if (!isSetUp) Protections.registerSuccess(activity)
            onDone(Protection(LockType.BIOMETRIC, ""))
        })
    }
    if (type == LockType.BIOMETRIC && !isSetUp) {
        LaunchedEffect(biometrics) { if (biometrics == BiometricState.READY) authenticate() }
    }

    val shownText = when {
        lockout > 0 -> pluralStringResource(R.plurals.too_many_incorrect_attempts, lockout, lockout)
        else -> message ?: defaultText
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            shownText,
            style = MaterialTheme.typography.titleMedium,
            color = if (isError || lockout > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 48.dp),
        )
        when (type) {
            LockType.PIN -> PinPad(
                pin = pin,
                onDigit = { if (pin.length < MAX_PIN_LENGTH) pin += it },
                onBackspace = { pin = pin.dropLast(1) },
                onConfirm = {
                    val entered = pin
                    pin = ""
                    entered(
                        entered,
                        isLongEnough = entered.length >= MINIMUM_PIN_LENGTH,
                        tooShort = activity.getString(R.string.pin_must_be_4_digits_long),
                        repeat = activity.getString(R.string.repeat_pin),
                        wrong = activity.getString(R.string.wrong_pin),
                    )
                },
                enabled = lockout == 0,
            )

            LockType.PATTERN -> PatternPad(
                onComplete = { dots ->
                    entered(
                        dots.joinToString(""),
                        isLongEnough = dots.size >= MINIMUM_PATTERN_DOTS,
                        tooShort = activity.getString(R.string.pattern_too_short),
                        repeat = activity.getString(R.string.repeat_pattern),
                        wrong = activity.getString(R.string.wrong_pattern),
                    )
                },
                resetKey = patternKey,
                isWrong = patternWrong,
                enabled = lockout == 0 && !patternWrong,
            )

            LockType.BIOMETRIC -> if (biometrics == BiometricState.READY) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                SignalButton(onClick = ::authenticate) { Text(stringResource(R.string.authenticate)) }
            } else {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
                Text(
                    stringResource(R.string.fingerprint_not_set_up_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                SignalButton(onClick = activity::openFingerprintSetup) { Text(stringResource(R.string.fingerprint_set_up_in_android)) }
            }
        }
    }
}

/**
 * The lock screen in front of everything while the app lock is closed. Back leaves the app instead of getting past it.
 */
@Composable
fun AppLockScreen() {
    val activity = LocalActivity.current ?: return
    val protection = remember { Protections.get(activity, ProtectionKind.APP) }
    if (protection == null) {
        LaunchedEffect(Unit) { AppLock.unlock() }
        return
    }
    BackHandler { activity.finishAffinity() }
    val icon = remember { LauncherIcon.resolve(activity) }
    Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize().designBackground(LocalDesignColors.current),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScrollWithBar().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIconImage(icon, 72.dp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.app_locked, stringResource(R.string.brand_name)), style = MaterialTheme.typography.headlineSmall)
            LockEntry(protection.type, expected = protection.sealed, onDone = { AppLock.unlock() })
        }
    }
}

/** The system's fingerprint (or face) dialog; a cancelled one simply closes. */
private fun Activity.authenticateBiometric(onSuccess: () -> Unit) {
    val prompt = BiometricPrompt.Builder(this)
        .setTitle(getString(R.string.authenticate))
        .setNegativeButton(getString(R.string.cancel), mainExecutor) { _, _ -> }
        .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
    })
}

/** Android's own steps for adding a fingerprint, the screen lock first if there is none yet. */
private fun Activity.openFingerprintSetup() {
    val enroll = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
        .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
    try {
        startActivity(enroll)
    } catch (e: ActivityNotFoundException) {
        // some devices only have the security settings for it
        runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }
}

private const val MINIMUM_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 10
private const val MINIMUM_PATTERN_DOTS = 4
private const val ERROR_DISPLAY_MS = 1000L
