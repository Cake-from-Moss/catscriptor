package org.cfm.catscriptor.ui.navigation

import androidx.compose.runtime.saveable.Saver

enum class TopTab { HOME, RECORDINGS }

/** Дочерние экраны поверх вкладок; на них нет нижней навигации. */
sealed interface ChildScreen {
    data class Transcript(val recordingId: String) : ChildScreen
    data object Settings : ChildScreen
}

/** Кодирует экран строкой, чтобы он переживал смену конфигурации без Parcelable. */
internal val ChildScreenSaver = Saver<ChildScreen?, String>(
    save = { screen ->
        when (screen) {
            is ChildScreen.Transcript -> "transcript:${screen.recordingId}"
            ChildScreen.Settings -> "settings"
            null -> ""
        }
    },
    restore = { value ->
        when {
            value.startsWith("transcript:") -> ChildScreen.Transcript(value.removePrefix("transcript:"))
            value == "settings" -> ChildScreen.Settings
            else -> null
        }
    },
)

/**
 * Навигационные действия для экранов. Экраны не знают о стеке, только вызывают эти функции.
 * [showProcessingDetails] открывает на главном подробную карточку этапов.
 */
data class AppNavigator(
    val openTranscript: (recordingId: String) -> Unit,
    val openSettings: () -> Unit,
    val openTab: (TopTab) -> Unit,
    val showProcessingDetails: () -> Unit,
    val back: () -> Unit,
)
