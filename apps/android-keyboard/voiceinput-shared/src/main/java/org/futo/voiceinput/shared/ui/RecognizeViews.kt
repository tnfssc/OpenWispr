package org.futo.voiceinput.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import org.futo.voiceinput.shared.R
import org.futo.voiceinput.shared.types.MagnitudeState
import org.futo.voiceinput.shared.ui.theme.Typography

data class MicrophoneDeviceState(
    val bluetoothAvailable: Boolean,
    val bluetoothActive: Boolean,
    val bluetoothPreferredByUser: Boolean,
    val setBluetooth: (Boolean) -> Unit,
    val deviceName: String
)

@Composable
private fun BoxScope.BluetoothToggleIcon(device: MutableState<MicrophoneDeviceState>? = null) {
    if(device?.value?.bluetoothAvailable == true) {
        val bluetoothColor = MaterialTheme.colorScheme.primary
        val iconColor = if(device.value.bluetoothActive) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        IconButton(modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = (-16).dp, y = (-16).dp)
            .drawBehind {
                val radius = size.height / 4.0f
                drawRoundRect(
                    bluetoothColor,
                    topLeft = Offset(size.width * 0.1f, size.height * 0.05f),
                    size = Size(size.width * 0.8f, size.height * 0.9f),
                    cornerRadius = CornerRadius(radius, radius),
                    style = if (device.value.bluetoothActive) {
                        Fill
                    } else {
                        Stroke(width = 4.0f)
                    }
                )
            }
            .clearAndSetSemantics {
                this.text = AnnotatedString("Use bluetooth mic")
                this.role = Role.Switch
                this.toggleableState = ToggleableState(device.value.bluetoothActive)
            },
            onClick = {
                device.value.setBluetooth(!device.value.bluetoothActive)
            },
        ) {
            Icon(
                painter = painterResource(id = R.drawable.bluetooth),
                contentDescription = null,
                tint = iconColor
            )
        }
    }
}

@Composable
fun InnerRecognize(
    magnitude: MutableFloatState = mutableFloatStateOf(0.5f),
    state: MutableState<MagnitudeState> = mutableStateOf(MagnitudeState.MIC_MAY_BE_BLOCKED),
    device: MutableState<MicrophoneDeviceState>? = null,
    processing: Boolean = false,
    animated: Boolean = true,
    onFinish: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val finishLabel = stringResource(R.string.stop_recording)
            Box(
                Modifier.size(160.dp, 136.dp).clickable(
                    enabled = !processing,
                    role = Role.Button,
                    onClickLabel = finishLabel,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onFinish,
                ).semantics { this.text = AnnotatedString(finishLabel) },
                contentAlignment = Alignment.Center,
            ) {
                OpenWisprLogo(Modifier.size(132.dp), magnitude.floatValue, processing, animated)
            }
            Text(
                when {
                    processing -> stringResource(R.string.transcribing)
                    state.value == MagnitudeState.MIC_MAY_BE_BLOCKED -> stringResource(R.string.microphone_unavailable)
                    else -> stringResource(R.string.listening)
                },
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (!processing) BluetoothToggleIcon(device)
    }
}


@Composable
fun ColumnScope.RecognizeLoadingCircle(text: String = "Initializing...") {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(text, modifier = Modifier.align(Alignment.CenterHorizontally))
}

@Composable
fun ColumnScope.PartialDecodingResult(text: String = "I am speaking [...]") {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        color = MaterialTheme.colorScheme.onPrimary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Surface(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(8.dp)
                .defaultMinSize(0.dp, 64.dp),
            textAlign = TextAlign.Start,
            style = Typography.bodyMedium
        )
    }
}

@Composable
fun ColumnScope.RecognizeMicError(openSettings: () -> Unit) {
    Box(Modifier
        .fillMaxSize()
        .clickable { openSettings() }) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.grant_microphone_permission_to_use_voice_input),
                modifier = Modifier
                    .padding(8.dp, 2.dp)
                    .align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.grant_microphone_permission_to_use_voice_input),
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
