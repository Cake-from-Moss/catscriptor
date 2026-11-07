package org.cfm.catscriptor.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.R
import org.cfm.catscriptor.domain.app.ExportFormat
import org.cfm.catscriptor.domain.app.ModelsStatus
import org.cfm.catscriptor.ui.components.AppIcons
import org.cfm.catscriptor.ui.recordings.speakersCountLabel
import org.cfm.catscriptor.ui.theme.StenografDimens
import org.cfm.catscriptor.ui.theme.StenografType
import org.cfm.catscriptor.viewmodel.settings.SPEAKER_COUNT_OPTIONS
import org.cfm.catscriptor.viewmodel.settings.SUPPORTED_LANGUAGES
import org.cfm.catscriptor.viewmodel.settings.SettingsUiState

class SettingsActions(
    val onBack: () -> Unit,
    val onLanguageChange: (String) -> Unit,
    val onSpeakerCountChange: (Int?) -> Unit,
    val onExportFormatChange: (ExportFormat) -> Unit,
    val onLiveTextChange: (Boolean) -> Unit,
)

@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(top = top).height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(AppIcons.Back, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.settings_title),
                style = StenografType.sheetTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp).semantics { heading() },
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
            section(R.string.settings_models) { ModelsItem(state.modelsStatus) }
            section(R.string.settings_language) {
                SUPPORTED_LANGUAGES.forEach { language ->
                    RadioItem(
                        title = languageName(language),
                        supporting = languageNote(language),
                        selected = state.settings.language == language,
                        onSelect = { actions.onLanguageChange(language) },
                    )
                }
            }
            section(R.string.settings_speakers) {
                SPEAKER_COUNT_OPTIONS.forEach { count ->
                    RadioItem(
                        title = if (count == null) stringResource(R.string.settings_speakers_auto) else speakersCountLabel(count),
                        supporting = if (count == null) stringResource(R.string.settings_speakers_auto_body) else null,
                        selected = state.settings.speakerCount == count,
                        onSelect = { actions.onSpeakerCountChange(count) },
                    )
                }
            }
            section(R.string.settings_export) {
                ExportFormat.entries.forEach { format ->
                    RadioItem(
                        title = stringResource(
                            when (format) {
                                ExportFormat.TXT -> R.string.export_format_txt
                                ExportFormat.JSON -> R.string.export_format_json
                                ExportFormat.SRT -> R.string.export_format_srt
                            },
                        ),
                        supporting = null,
                        selected = state.settings.defaultExportFormat == format,
                        onSelect = { actions.onExportFormatChange(format) },
                    )
                }
            }
            section(R.string.settings_live, divider = false) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_live_text)) },
                    supportingContent = { Text(stringResource(R.string.settings_live_text_body)) },
                    trailingContent = { Switch(checked = state.settings.liveTextEnabled, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.toggleable(
                        value = state.settings.liveTextEnabled,
                        role = Role.Switch,
                        onValueChange = actions.onLiveTextChange,
                    ),
                )
            }
        }
    }
}

private fun LazyListScope.section(@StringRes title: Int, divider: Boolean = true, content: @Composable () -> Unit) {
    item(key = title) {
        Column {
            Text(
                stringResource(title),
                style = StenografType.label,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = StenografDimens.ScreenPadding)
                    .padding(top = 20.dp, bottom = 4.dp)
                    .semantics { heading() },
            )
            content()
            if (divider) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp, start = StenografDimens.ScreenPadding, end = StenografDimens.ScreenPadding),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelsItem(status: ModelsStatus) {
    val ready = status is ModelsStatus.Ready
    val details = (status as? ModelsStatus.Unavailable)?.details
    ListItem(
        headlineContent = {
            Text(stringResource(if (ready) R.string.settings_models_ready else R.string.settings_models_unavailable))
        },
        supportingContent = {
            Text(
                details?.takeIf { it.isNotBlank() }
                    ?: stringResource(if (ready) R.string.settings_models_ready_body else R.string.settings_models_unavailable_body),
            )
        },
        leadingContent = {
            Icon(
                if (ready) AppIcons.Check else AppIcons.Warning,
                contentDescription = null,
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun RadioItem(title: String, supporting: String?, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

@Composable
private fun languageName(code: String): String = when (code) {
    "ru" -> stringResource(R.string.settings_language_ru)
    else -> code
}

@Composable
private fun languageNote(code: String): String? = when (code) {
    "ru" -> stringResource(R.string.settings_language_ru_body)
    else -> null
}
