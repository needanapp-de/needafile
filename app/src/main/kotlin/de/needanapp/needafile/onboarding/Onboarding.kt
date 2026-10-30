package de.needanapp.needafile.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.needanapp.design.components.OnboardingAppearance
import de.needanapp.design.components.OnboardingButton
import de.needanapp.design.components.OnboardingPoint
import de.needanapp.design.components.OnboardingScreen
import de.needanapp.design.components.OnboardingStatusRow
import de.needanapp.design.theme.LocalIsDarkTheme
import de.needanapp.design.theme.ThemeSettings
import de.needanapp.design.theme.activeDarkWorld
import de.needanapp.design.theme.activeLightWorld
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.permissions.StorageAccess
import de.needanapp.needafile.settings.APP_STYLES
import de.needanapp.needafile.settings.config

/** When the introduction shows: once after installing, never after an update of an app already in use. */
object Onboarding {
    /** Whether to show it: freshly installed and not yet gone through or skipped. */
    fun shouldShow(context: Context): Boolean {
        if (context.config.onboardingDone) return false
        // an update changes the time of the last update; a fresh install has both the same
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.firstInstallTime == info.lastUpdateTime
    }

    fun finish(context: Context) {
        context.config.onboardingDone = true
    }
}

/** The steps of the introduction, in order. */
private enum class Step(@StringRes val label: Int) {
    WELCOME(R.string.onboarding_step_welcome),
    FILES(R.string.onboarding_step_files),
    NOTIFICATIONS(R.string.onboarding_step_notifications),
    APPEARANCE(R.string.onboarding_step_appearance),
    DONE(R.string.onboarding_step_done),
}

/**
 * The introduction: what needafile does, access to all files, notifications, the look and what came of it, each step
 * below a preview of the files that follows it. "Skip" and the last step end it with [onDone]; back goes a step back.
 */
@Composable
fun OnboardingFlow(activity: BaseActivity, settings: ThemeSettings, onDone: () -> Unit) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = Step.entries[stepIndex]
    var filesAllowed by remember { mutableStateOf(StorageAccess.isGranted()) }
    var notificationsAllowed by remember { mutableStateOf(activity.notificationsAllowed()) }
    // Android asks no more after two refusals; then only the app's notification settings help
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    val brand = stringResource(R.string.brand_name)
    val next = { stepIndex = (stepIndex + 1).coerceAtMost(Step.entries.lastIndex) }

    // back from the system settings
    LifecycleResumeEffect(Unit) {
        filesAllowed = StorageAccess.isGranted()
        notificationsAllowed = activity.notificationsAllowed()
        onPauseOrDispose {}
    }
    BackHandler(enabled = stepIndex > 0) { stepIndex-- }

    fun askForNotifications() {
        if (notificationsBlocked) {
            activity.openNotificationSettings()
            return
        }
        activity.withNotificationPermission {
            notificationsAllowed = activity.notificationsAllowed()
            if (!notificationsAllowed) {
                notificationsBlocked = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    OnboardingScreen(
        step = stepIndex,
        stepCount = Step.entries.size,
        stepName = stringResource(step.label),
        title = when (step) {
            Step.WELCOME -> stringResource(R.string.onboarding_welcome_title, brand)
            Step.FILES -> stringResource(R.string.storage_access_title)
            Step.NOTIFICATIONS -> stringResource(R.string.onboarding_step_notifications)
            Step.APPEARANCE -> stringResource(R.string.onboarding_step_appearance)
            Step.DONE -> stringResource(R.string.onboarding_done_title)
        },
        badge = if (step == Step.NOTIFICATIONS) stringResource(R.string.onboarding_optional) else null,
        onSkip = if (step != Step.DONE) onDone else null,
        preview = {
            OnboardingPreview(
                filesAllowed = filesAllowed,
                showNoAccess = step == Step.FILES && !filesAllowed,
                showNotifications = step == Step.NOTIFICATIONS,
            )
        },
        actions = {
            when {
                step == Step.FILES && !filesAllowed -> {
                    OnboardingButton(stringResource(R.string.onboarding_later), onClick = next, filled = false)
                    OnboardingButton(stringResource(R.string.storage_access_allow), onClick = { StorageAccess.openSettings(activity) }, icon = Icons.AutoMirrored.Outlined.OpenInNew)
                }
                step == Step.NOTIFICATIONS && !notificationsAllowed -> {
                    OnboardingButton(stringResource(R.string.onboarding_later), onClick = next, filled = false)
                    OnboardingButton(
                        stringResource(if (notificationsBlocked) R.string.onboarding_open_notification_settings else R.string.onboarding_allow),
                        onClick = ::askForNotifications,
                    )
                }
                step == Step.DONE -> OnboardingButton(stringResource(R.string.onboarding_start), onClick = onDone)
                else -> OnboardingButton(stringResource(R.string.onboarding_next), onClick = next)
            }
        },
    ) {
        when (step) {
            Step.WELCOME -> {
                Text(stringResource(R.string.onboarding_welcome_text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
                OnboardingPoint(Icons.Outlined.PhoneAndroid, stringResource(R.string.onboarding_point_phone))
                OnboardingPoint(Icons.Outlined.Dns, stringResource(R.string.onboarding_point_servers))
                OnboardingPoint(Icons.Outlined.Lock, stringResource(R.string.onboarding_point_vaults))
            }

            Step.FILES -> if (filesAllowed) {
                Description(stringResource(R.string.onboarding_files_done))
                OnboardingStatusRow(Icons.Outlined.Folder, stringResource(R.string.onboarding_files_row), stringResource(R.string.onboarding_allowed), done = true)
            } else {
                Description(stringResource(R.string.storage_access_message, brand))
            }

            Step.NOTIFICATIONS -> {
                Description(stringResource(R.string.onboarding_notifications_text, brand))
                when {
                    notificationsAllowed -> OnboardingStatusRow(
                        Icons.Outlined.Notifications,
                        stringResource(R.string.onboarding_step_notifications),
                        stringResource(R.string.onboarding_allowed),
                        done = true,
                    )
                    notificationsBlocked -> Description(stringResource(R.string.onboarding_notifications_blocked))
                }
            }

            Step.APPEARANCE -> {
                Description(stringResource(R.string.onboarding_appearance_text))
                OnboardingAppearance(settings, styles = APP_STYLES)
            }

            Step.DONE -> {
                Description(stringResource(R.string.onboarding_done_text, brand))
                Summary(settings, filesAllowed, notificationsAllowed)
            }
        }
    }
}

@Composable
private fun Description(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 14.dp),
    )
}

/** What came of the steps: access, notifications and the look ("NeedAnApp · Dark · Nord night"). */
@Composable
private fun Summary(settings: ThemeSettings, filesAllowed: Boolean, notificationsAllowed: Boolean) {
    val isDark = LocalIsDarkTheme.current
    val world = if (isDark) settings.activeDarkWorld.label else settings.activeLightWorld.label
    val look = listOf(stringResource(settings.design.label), stringResource(settings.mode.label), stringResource(world)).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OnboardingStatusRow(
            Icons.Outlined.Folder,
            stringResource(R.string.onboarding_step_files),
            stringResource(if (filesAllowed) R.string.onboarding_allowed else R.string.onboarding_not_allowed),
            done = filesAllowed,
        )
        OnboardingStatusRow(
            Icons.Outlined.Notifications,
            stringResource(R.string.onboarding_step_notifications),
            stringResource(if (notificationsAllowed) R.string.onboarding_allowed else R.string.onboarding_off),
            done = notificationsAllowed,
        )
        OnboardingStatusRow(Icons.Outlined.Palette, stringResource(R.string.onboarding_step_appearance), look, done = false)
    }
}

/** Whether needafile's notifications show: allowed and not switched off for the app. */
private fun Context.notificationsAllowed() = NotificationManagerCompat.from(this).areNotificationsEnabled()

private fun Context.openNotificationSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}
