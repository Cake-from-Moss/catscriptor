package org.cfm.catscriptor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cfm.catscriptor.audio.AudioImportController
import org.cfm.catscriptor.audio.background.AudioProcessingController
import org.cfm.catscriptor.audio.background.AudioProcessingPhase
import org.cfm.catscriptor.domain.pipeline.AudioInput
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var audioImport: AudioImportController
    private var pendingAudio: AudioInput? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingAudio?.let { input -> pendingAudio = null; AudioProcessingController.start(this, input) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAudio = savedInstanceState?.getString("pending_audio_uri")?.let(::AudioInput)
        audioImport = AudioImportController(this, ::onAudioSelected)
        setContent {
            val state by AudioProcessingController.state.collectAsState()
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text("Catscriptor", style = MaterialTheme.typography.headlineMedium)
                        Text("Подготовьте локальную запись для дальнейшей обработки. Файл остаётся на устройстве.", style = MaterialTheme.typography.bodyLarge)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Аудиозапись", style = MaterialTheme.typography.titleMedium)
                                Text("Импортируйте аудио с устройства. Сейчас выполняется только подготовка аудио — расшифровка ещё не подключена.", style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { audioImport.launch() }, enabled = state.phase != AudioProcessingPhase.RUNNING) {
                                    Text("Выбрать аудиофайл")
                                }
                            }
                        }
                        when (state.phase) {
                            AudioProcessingPhase.RUNNING -> Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Подготовка аудио", style = MaterialTheme.typography.titleMedium)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(state.message ?: "Обработка может продолжаться в фоне.", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = { AudioProcessingController.cancel(this@MainActivity) }) { Text("Отменить") }
                                }
                            }
                            AudioProcessingPhase.COMPLETED -> Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Подготовка завершена", style = MaterialTheme.typography.titleMedium)
                                    Text("Длительность аудио: ${formatDuration(state.sampleCount)}", style = MaterialTheme.typography.bodyLarge)
                                    Text("Это только подготовка аудио; текст и расшифровка не создавались.", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            AudioProcessingPhase.CANCELLED -> Text("Подготовка отменена.", style = MaterialTheme.typography.bodyMedium)
                            AudioProcessingPhase.FAILED -> Text(state.message ?: "Не удалось подготовить аудио.", color = MaterialTheme.colorScheme.error)
                            AudioProcessingPhase.IDLE -> Unit
                        }
                        Text("Чтобы система реже ограничивала работу в фоне, можно настроить режим батареи. Названия пунктов зависят от устройства.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { openBatterySettings() }) { Text("Настроить работу в фоне") }
                    }
                }
            }
        }
    }

    private fun onAudioSelected(result: Result<AudioInput>) {
        result.onSuccess { input ->
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingAudio = input
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else AudioProcessingController.start(this, input)
        }.onFailure { error ->
            android.widget.Toast.makeText(this, error.message ?: "Не удалось открыть аудиофайл", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingAudio?.let { outState.putString("pending_audio_uri", it.documentId) }
        super.onSaveInstanceState(outState)
    }

    private fun openBatterySettings() {
        val settings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            startActivity(settings)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "Откройте настройки батареи устройства и разрешите работу приложения в фоне.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatDuration(samples: Long): String {
        val seconds = samples / 16_000
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }

    /** Загружает JNI-библиотеку приложения. */
    external fun stringFromJNI(): String

    companion object { init { System.loadLibrary("catscriptor") } }
}
