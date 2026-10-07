package org.futo.inputmethod.latin.uix.settings.pages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.futo.inputmethod.latin.openwispr.*
import org.futo.voiceinput.shared.*
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState

class VoiceSetupStore(context: Context) {
    private val preferences = context.getSharedPreferences("openwispr_voice_setup", Context.MODE_PRIVATE)
    fun load(configured: Boolean) = VoiceSetupProgress(
        step = preferences.getInt("step", 0).coerceIn(0, 2),
        active = preferences.getBoolean("active", !configured),
        testPassed = preferences.getBoolean("tested", false),
        completed = preferences.getBoolean("completed", false),
    )
    fun save(value: VoiceSetupProgress) {
        preferences.edit().putInt("step", value.step).putBoolean("active", value.active)
            .putBoolean("tested", value.testPassed).putBoolean("completed", value.completed).apply()
    }
}

@Composable
fun VoiceSetupHeader(progress: VoiceSetupProgress, update: (VoiceSetupProgress) -> Unit) {
    if (!progress.active) {
        Text(if (progress.completed) "Voice setup complete" else "Try your first dictation")
        Button(onClick = { update(progress.reopen()) }) {
            Text(if (progress.completed) "Reopen voice setup" else "Open voice setup")
        }
        return
    }
    Text("Voice setup · ${progress.step + 1} of 3", style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(progress = { (progress.step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
    Row {
        TextButton(enabled = progress.step > 0, onClick = { update(progress.back()) }) { Text("Back") }
        TextButton(onClick = { update(progress.leave()) }) { Text("Leave setup") }
    }
}

@Composable
fun VoiceSetupMicrophone(onNext: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    Text("Allow the microphone, then try a short recording. Android's microphone switch must also be on.")
    Button(enabled = !granted, onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
        Text(if (granted) "Microphone allowed" else "Allow microphone")
    }
    if (denied && !granted) {
        Text("Microphone permission is off. You can allow it in Android app settings.")
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        }) { Text("Open app permissions") }
    }
    Button(enabled = granted, onClick = onNext) { Text("Next: try dictation") }
}

@Composable
fun VoiceSetupTest(progress: VoiceSetupProgress, update: (VoiceSetupProgress) -> Unit, onSettings: () -> Unit, onRecoveryChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycleScope
    val store = remember { FailedSpeechStore(context.noBackupFilesDir) }
    var recovery by remember { mutableStateOf(store.exists()) }
    var view by remember { mutableStateOf<RecognizerView?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    val latestProgress by rememberUpdatedState(progress)
    val latestUpdate by rememberUpdatedState(update)
    val latestRecoveryChanged by rememberUpdatedState(onRecoveryChanged)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { view?.cancel(); view = null }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.cancel()
            view = null
        }
    }

    Text("Tap the OpenWispr button on the keyboard to speak. Tap stop when done; normal dictation inserts into the active input. Cancel removes live preview.")
    Text("Try saying ‘This is my first dictation.’ This test only shows text here. It will not type into another app.")
    if (recovery) {
        // Resolve saved speech before any new test can overwrite it.
        SpeechRecoveryControls(store, onResolved = { recovery = false; latestRecoveryChanged(false) }, onSettings = onSettings,
            onTestResult = { text -> latestRecoveryChanged(false); latestUpdate(latestProgress.tested(text)) })
    } else {
        if (view != null) Box(Modifier.fillMaxWidth().height(180.dp)) { view?.Content() }
        if (!recording) Button(onClick = {
            result = null
            failure = null
            val config = OpenWisprConfigStore.load(context)
            if (!config.isConfigured) {
                failure = "Save your provider and API key first."
                return@Button
            }
            val listener = object : RecognizerViewListener {
                override fun cancelled() {
                    recording = false
                    recovery = store.exists()
                    latestRecoveryChanged(recovery)
                }
                override fun recordingStarted(device: MicrophoneDeviceState) = Unit
                override fun finished(text: String) {
                    recording = false
                    result = text.ifBlank { "No speech was recognized. Try again." }
                    latestUpdate(latestProgress.tested(text))
                    view?.cancel()
                    view = null
                }
                override fun partialResult(result: String) = Unit
                override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean = false
                override fun openSettings() = onSettings()
            }
            view?.cancel()
            view = RecognizerView(context, listener,
                RecognizerViewSettings(false, false, true, "Check voice settings and try again.",
                    RecoverableSpeechBackend(OpenWisprTranscriptionBackend(config), store) { failure = it },
                    RecordingSettings(false, true, true, false)), lifecycle)
            recording = true
            view?.reset()
            view?.start()
        }) { Text("Start test") }
        if (recording) Row {
            Button(onClick = { view?.finish() }) { Text("Stop") }
            TextButton(onClick = { view?.cancel(); view = null }) { Text("Cancel") }
        }
        failure?.let { Text(it) }
        result?.let { Text(it) }
    }
    Button(enabled = progress.testPassed && !recording && !recovery, onClick = { update(progress.finish()) }) { Text("Finish setup") }
}
