package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.openwispr.OpenWisprConfig
import org.futo.inputmethod.latin.openwispr.OpenWisprConfigStore
import org.futo.inputmethod.latin.openwispr.OpenWisprProvider
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly

val OpenWisprSpeechMenu = UserSettingsMenu(
    title = R.string.openwispr_speech_provider_title,
    navPath = "openwisprVoice",
    registerNavPath = true,
    settings = listOf(userSettingDecorationOnly { OpenWisprSpeechSettings() }),
)

val OpenWisprRefinementMenu = UserSettingsMenu(
    title = R.string.openwispr_refinement_title,
    navPath = "openwisprRefinement",
    registerNavPath = true,
    settings = listOf(userSettingDecorationOnly { OpenWisprRefinementSettings() }),
)

@Composable
private fun OpenWisprSpeechSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(OpenWisprConfigStore.load(context)) }
    var saved by remember { mutableStateOf(false) }

    SettingsForm {
        NetworkDisclosure(config.secureStorageAvailable)
        ProviderPicker(
            title = "Speech provider",
            selected = config.provider,
            onSelect = { config = config.copy(provider = it); saved = false },
        )
        SecretField(
            value = config.keyFor(config.provider),
            label = "${config.provider.displayName} API key",
            onValueChange = {
                config = when (config.provider) {
                    OpenWisprProvider.GROQ -> config.copy(groqApiKey = it)
                    OpenWisprProvider.OPEN_ROUTER -> config.copy(openRouterApiKey = it)
                }
                saved = false
            },
        )
        OutlinedTextField(
            value = config.modelFor(config.provider),
            onValueChange = {
                config = when (config.provider) {
                    OpenWisprProvider.GROQ -> config.copy(groqModel = it)
                    OpenWisprProvider.OPEN_ROUTER -> config.copy(openRouterModel = it)
                }
                saved = false
            },
            label = { Text("Transcription model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.language,
            onValueChange = { config = config.copy(language = it); saved = false },
            label = { Text("Language code") },
            supportingText = { Text("Blank detects language automatically. Example: en, es, ar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SaveButton(
            saved = saved,
            enabled = config.apiKey.isNotBlank() && config.model.isNotBlank(),
        ) {
            scope.launch {
                withContext(Dispatchers.IO) { OpenWisprConfigStore.save(context, config) }
                saved = true
            }
        }
    }
}

@Composable
private fun OpenWisprRefinementSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(OpenWisprConfigStore.load(context)) }
    var saved by remember { mutableStateOf(false) }

    SettingsForm {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Refine transcript", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If refinement fails, raw transcript is inserted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = config.refinementEnabled,
                onCheckedChange = { config = config.copy(refinementEnabled = it); saved = false },
            )
        }
        ProviderPicker(
            title = "Refinement provider",
            selected = config.refinementProvider,
            onSelect = { config = config.copy(refinementProvider = it); saved = false },
        )
        SecretField(
            value = config.keyFor(config.refinementProvider),
            label = "${config.refinementProvider.displayName} API key",
            onValueChange = {
                config = when (config.refinementProvider) {
                    OpenWisprProvider.GROQ -> config.copy(groqApiKey = it)
                    OpenWisprProvider.OPEN_ROUTER -> config.copy(openRouterApiKey = it)
                }
                saved = false
            },
        )
        OutlinedTextField(
            value = config.refinementModel,
            onValueChange = {
                config = when (config.refinementProvider) {
                    OpenWisprProvider.GROQ -> config.copy(groqRefinementModel = it)
                    OpenWisprProvider.OPEN_ROUTER -> config.copy(openRouterRefinementModel = it)
                }
                saved = false
            },
            label = { Text("Refinement model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.refinementPrompt,
            onValueChange = { config = config.copy(refinementPrompt = it); saved = false },
            label = { Text("Refinement instructions") },
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        SaveButton(
            saved = saved,
            enabled = !config.refinementEnabled || (
                config.keyFor(config.refinementProvider).isNotBlank() &&
                    config.refinementModel.isNotBlank() &&
                    config.refinementPrompt.isNotBlank()
                ),
        ) {
            scope.launch {
                withContext(Dispatchers.IO) { OpenWisprConfigStore.save(context, config) }
                saved = true
            }
        }
    }
}

@Composable
private fun SettingsForm(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        content()
    }
}

@Composable
private fun NetworkDisclosure(secureStorageAvailable: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Voice recordings are sent to the selected provider. Keyboard typing, predictions, and swipe input remain local.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (secureStorageAvailable) {
                "API keys are encrypted on this device."
            } else {
                "Secure storage is unavailable. API keys remain only until this process stops."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProviderPicker(
    title: String,
    selected: OpenWisprProvider,
    onSelect: (OpenWisprProvider) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpenWisprProvider.entries.forEach { provider ->
                FilterChip(
                    selected = provider == selected,
                    onClick = { onSelect(provider) },
                    label = { Text(provider.displayName) },
                )
            }
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SaveButton(saved: Boolean, enabled: Boolean, onSave: () -> Unit) {
    Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(if (saved) "Saved" else "Save")
    }
}
