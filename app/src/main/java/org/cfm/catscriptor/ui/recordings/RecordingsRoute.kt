package org.cfm.catscriptor.ui.recordings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.cfm.catscriptor.R
import org.cfm.catscriptor.app.AppGraph
import org.cfm.catscriptor.ui.export.ExportSheetRoute
import org.cfm.catscriptor.ui.navigation.AppNavigator
import org.cfm.catscriptor.ui.navigation.LocalAppSnackbar
import org.cfm.catscriptor.ui.navigation.ScopedViewModels
import org.cfm.catscriptor.ui.navigation.TopTab
import org.cfm.catscriptor.viewmodel.recordings.RecordingOpenTarget
import org.cfm.catscriptor.viewmodel.recordings.RecordingsViewModel

/** Окно «Отменить» после удаления записи. */
private const val UNDO_TIMEOUT_MS = 5_000L

@Composable
fun RecordingsRoute(graph: AppGraph, navigator: AppNavigator) {
    val viewModel = viewModel(key = "recordings") { RecordingsViewModel(graph.recordings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = LocalAppSnackbar.current
    val deletedMessage = stringResource(R.string.recordings_deleted)
    val undoLabel = stringResource(R.string.recordings_undo)

    val rowActions = remember(viewModel, navigator, snackbar, deletedMessage, undoLabel) {
        RecordingRowActions(
            onOpen = { summary ->
                when (val target = viewModel.openTarget(summary)) {
                    is RecordingOpenTarget.Transcript -> navigator.openTranscript(target.recordingId)
                    RecordingOpenTarget.ProcessingDetails -> navigator.showProcessingDetails()
                }
            },
            onRename = viewModel::onRenameRequest,
            onExport = viewModel::onExportRequest,
            onDelete = { summary ->
                viewModel.onDelete(summary)
                snackbar.show(
                    message = deletedMessage,
                    actionLabel = undoLabel,
                    onAction = { viewModel.onUndoDelete(summary.id) },
                    onTimeout = { viewModel.onDeleteConfirmed(summary.id) },
                    timeoutMs = UNDO_TIMEOUT_MS,
                )
            },
        )
    }

    RecordingsScreen(
        state = state,
        query = query,
        onSearchChange = viewModel::onSearchChange,
        onFilterChange = viewModel::onFilterChange,
        onGoToRecord = { navigator.openTab(TopTab.HOME) },
        rowActions = rowActions,
    )

    state.renaming?.let { recording ->
        TextInputDialog(
            title = stringResource(R.string.recordings_rename_title),
            label = stringResource(R.string.recordings_rename_label),
            initialValue = recording.title,
            onConfirm = viewModel::onRenameConfirm,
            onDismiss = viewModel::onRenameDismiss,
        )
    }

    state.exportingId?.let { recordingId ->
        ScopedViewModels(key = "export-$recordingId") {
            ExportSheetRoute(graph = graph, recordingId = recordingId, onDismiss = viewModel::onExportDismiss)
        }
    }
}
