package de.needanapp.needafile.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.OptionsSheet
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.SwitchRow
import de.needanapp.design.components.verticalScrollWithBar
import de.needanapp.needafile.R
import de.needanapp.needafile.core.DATE_PATTERNS
import de.needanapp.needafile.core.TIME_PATTERN_12
import de.needanapp.needafile.core.TIME_PATTERN_24
import de.needanapp.needafile.settings.config
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * How dates and times of files are shown, each layout with today's date; every change applies at once and [onChanged]
 * reloads the lists.
 */
@Composable
fun DateTimeFormatSheet(onChanged: () -> Unit, onDismiss: () -> Unit) {
    val config = LocalContext.current.config
    var dateFormat by remember { mutableStateOf(config.dateFormat) }
    var use24Hours by remember { mutableStateOf(config.use24HourFormat) }
    val now = remember { System.currentTimeMillis() }
    fun format(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(now)

    OptionsSheet(onDismiss = onDismiss, title = stringResource(R.string.change_date_and_time_format), skipPartiallyExpanded = true) {
        Column(Modifier.verticalScrollWithBar()) {
            SettingsGroup(stringResource(R.string.settings_date_format)) {
                DATE_PATTERNS.forEach { pattern ->
                    row {
                        ListRow(it, format(pattern), onClick = {
                            dateFormat = pattern
                            config.dateFormat = pattern
                            onChanged()
                        }, trailing = { RadioButton(selected = dateFormat == pattern, onClick = null) })
                    }
                }
            }
            SettingsGroup(stringResource(R.string.settings_time_format)) {
                row {
                    SwitchRow(
                        it,
                        stringResource(R.string.use_24_hour_time_format),
                        use24Hours,
                        onCheckedChange = { value ->
                            use24Hours = value
                            config.use24HourFormat = value
                            onChanged()
                        },
                        summary = format(if (use24Hours) TIME_PATTERN_24 else TIME_PATTERN_12),
                    )
                }
            }
        }
    }
}
