package org.cfm.catscriptor.ui.home

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.R
import org.cfm.catscriptor.app.AppGraph
import org.cfm.catscriptor.ui.navigation.AppNavigator
import org.cfm.catscriptor.ui.navigation.LocalAppSnackbar
import org.cfm.catscriptor.ui.navigation.TopTab
import org.cfm.catscriptor.viewmodel.home.HomeEvent
import org.cfm.catscriptor.viewmodel.home.HomeViewModel

/**
 * Связывает главный экран с графом: ViewModel, системные диалоги (разрешение на микрофон,
 * выбор файла) и Snackbar. Решений о данных здесь нет, только Android-обвязка.
 */
@Composable
fun HomeRoute(graph: AppGraph, navigator: AppNavigator, processingDetailsRequest: Int) {
    val viewModel = viewModel { HomeViewModel(graph.recordings, graph.processing, graph.recorder, graph.settings) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val signal = viewModel.signal.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(processingDetailsRequest) { viewModel.onDetailsRequest(processingDetailsRequest) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.LowStorage ->
                    snackbar.show(resources.getString(R.string.home_low_storage, event.minutesAvailable))
                HomeEvent.RecordingUnavailable -> snackbar.show(resources.getString(R.string.home_recording_unavailable))
                HomeEvent.RecordingStopFailed -> snackbar.show(resources.getString(R.string.home_recording_stop_failed))
                HomeEvent.ImportUnsupported -> snackbar.show(resources.getString(R.string.home_import_unsupported))
                HomeEvent.ImportModelsUnavailable ->
                    snackbar.show(resources.getString(R.string.home_import_models_unavailable))
                HomeEvent.RecordingInterrupted -> snackbar.show(
                    message = resources.getString(R.string.home_recording_interrupted),
                    actionLabel = resources.getString(R.string.home_resume),
                    durationMs = SnackbarDuration.Long,
                    onAction = viewModel::resumeRecording,
                )
            }
        }
    }

    // Разрешение на микрофон. «Больше не спрашивать» видно только косвенно: после хотя бы одного
    // запроса система перестаёт советовать объяснение (shouldShowRequestPermissionRationale = false).
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var micAccess by remember { mutableStateOf(micAccessOf(context, permissionRequested)) }
    LifecycleResumeEffect(permissionRequested) {
        // Пользователь мог выдать разрешение в настройках системы и вернуться.
        micAccess = micAccessOf(context, permissionRequested)
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRequested = true
        micAccess = micAccessOf(context, requestedBefore = true)
        if (granted) viewModel.startRecording()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { prepareImport(context.contentResolver, uri) }
                viewModel.importFile(uri.toString(), name ?: resources.getString(R.string.home_untitled))
            }
        }
    }

    val actions = remember(viewModel, navigator) {
        HomeActions(
            onRecord = {
                when {
                    // Запись не реализована в сборке: разрешение не просим, честно говорим об этом.
                    !graph.recorder.isAvailable ->
                        snackbar.show(resources.getString(R.string.home_recording_unavailable))
                    micAccessOf(context, permissionRequested) == MicAccess.GRANTED -> viewModel.startRecording()
                    micAccess == MicAccess.OPEN_SETTINGS -> context.startActivity(appSettingsIntent(context))
                    else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onStop = viewModel::stopRecording,
            onToggleLiveText = viewModel::toggleLiveText,
            onUploadFile = { importLauncher.launch(arrayOf(AUDIO_MIME)) },
            onPause = viewModel::pauseRecording,
            onResume = viewModel::resumeRecording,
            onAddMark = viewModel::addMark,
            onOpenSettings = navigator.openSettings,
            onOpenRecording = navigator.openTranscript,
            onOpenAllRecordings = { navigator.openTab(TopTab.RECORDINGS) },
            onExpandJob = viewModel::showDetails,
            onCollapseJob = viewModel::collapseDetails,
            onCancelJob = viewModel::cancelProcessing,
            onRetryJob = viewModel::retryStage,
            onSkipStage = viewModel::skipStage,
        )
    }

    HomeScreen(
        state = state,
        signal = { signal.value },
        micAccess = micAccess,
        actions = actions,
    )
}

private const val AUDIO_MIME = "audio/*"

private fun micAccessOf(context: Context, requestedBefore: Boolean): MicAccess {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return MicAccess.GRANTED
    val activity = context.findActivity()
    val rationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
    return if (requestedBefore && !rationale) MicAccess.OPEN_SETTINGS else MicAccess.REQUEST
}

private fun appSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Закрепляет право на чтение (обработка может идти в фоне после закрытия экрана)
 * и возвращает имя файла из SAF. Провайдер может не дать постоянное право: тогда хватит временного.
 */
private fun prepareImport(resolver: ContentResolver, uri: Uri): String? {
    try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Провайдер не поддерживает постоянные права; временного права хватает на текущий сеанс.
    }
    val fromProvider = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')
}
