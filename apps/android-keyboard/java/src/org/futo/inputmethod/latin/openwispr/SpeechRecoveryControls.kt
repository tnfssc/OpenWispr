package org.futo.inputmethod.latin.openwispr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
fun SpeechRecoveryControls(
    store: FailedSpeechStore,
    onResolved: () -> Unit,
    onSettings: () -> Unit,
    onInsert: ((String) -> Unit)? = null,
    onTestResult: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(runCatching { store.message() }.getOrDefault("Saved recording could not be read. Discard it to record again.")) }
    var result by remember { mutableStateOf<String?>(null) }
    var backend by remember { mutableStateOf<OpenWisprTranscriptionBackend?>(null) }
    DisposableEffect(Unit) { onDispose { backend?.cancel() } }

    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (result == null) {
            Text(message)
            Text("Your failed recording stays on this device until retry succeeds or you discard it.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val config = withContext(Dispatchers.IO) { OpenWisprConfigStore.load(context) }
                            val current = OpenWisprTranscriptionBackend(config)
                            backend = current
                            val recovered = withContext(Dispatchers.IO) { retryFailedSpeech(store, current) }
                            store.discard(recovered.id)
                            result = recovered.text
                            onTestResult?.invoke(recovered.text)
                        } catch (error: CancellationException) { throw error
                        } catch (error: Exception) { message = speechFailureMessage(error)
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "Retrying…" else "Retry") }
                TextButton(enabled = !busy, onClick = {
                    runCatching { store.discard() }.onSuccess { onResolved() }
                        .onFailure { message = "Could not discard recording. Try again." }
                }) { Text("Discard") }
            }
            TextButton(enabled = !busy, onClick = onSettings) { Text("Voice settings") }
        } else {
            Text(result!!.ifBlank { "No speech was recognized." })
            Text("Nothing has been inserted.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onInsert != null && result!!.isNotBlank()) {
                    Button(onClick = { onInsert(result!!); onResolved() }) { Text("Insert here") }
                }
                if (result!!.isNotBlank()) {
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Dictation", result!!))
                        message = "Copied"
                    }) { Text(if (message == "Copied") "Copied" else "Copy") }
                }
                TextButton(onClick = onResolved) { Text("Done") }
            }
        }
    }
}
