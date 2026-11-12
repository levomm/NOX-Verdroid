package com.verdroid.app.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.network.CustomProviderStore
import com.verdroid.app.data.voice.RealtimePhase
import com.verdroid.app.data.voice.RealtimeProtocol
import com.verdroid.app.data.voice.RealtimeVoiceClient
import com.verdroid.app.data.voice.RealtimeVoiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltViewModel
class RealtimeVoiceViewModel @Inject constructor(
    @ApplicationContext context: Context,
    store: CustomProviderStore,
    http: OkHttpClient,
) : ViewModel() {
    private val client = RealtimeVoiceClient(context, http) { store.keyFor("openai") }
    val state: StateFlow<RealtimeVoiceState> = client.state
    private val _model = MutableStateFlow(RealtimeProtocol.DEFAULT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    fun selectModel(value: String) {
        if (value in RealtimeProtocol.MODELS && state.value.phase in listOf(RealtimePhase.IDLE, RealtimePhase.ERROR))
            _model.value = value
    }
    fun connect() = client.connect(_model.value)
    fun disconnect() = client.disconnect()
    override fun onCleared() { client.close(); super.onCleared() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealtimeVoiceScreen(
    onBack: () -> Unit,
    vm: RealtimeVoiceViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.connect()
    }
    // Never leave a paid recording running in the background.
    DisposableEffect(vm) { onDispose { vm.disconnect() } }
    val active = state.phase == RealtimePhase.LISTENING || state.phase == RealtimePhase.RESPONDING ||
        state.phase == RealtimePhase.CONNECTING

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("NOX · Realtime Voice") },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Live voice", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Separate OpenAI speech-to-speech conversation. Your normal Verdroid chat and its tools are not available in this voice mode.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Model", style = MaterialTheme.typography.titleSmall)
            RealtimeProtocol.MODELS.forEach { option ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(if (option.endsWith("-mini")) "Realtime 2.1 mini · lower cost" else "Realtime 2.1 · higher quality")
                        Text(option, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(
                        selected = model == option,
                        onClick = { vm.selectModel(option) },
                        enabled = !active
                    )
                }
            }
            Text("State: ${state.message}", style = MaterialTheme.typography.titleMedium,
                color = if (state.phase == RealtimePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (state.transcript.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Text(state.transcript, Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            if (!active) {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            vm.connect()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect and start voice") }
            } else {
                OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect and stop microphone")
                }
            }
            Text(
                "Uses your own encrypted OpenAI API key from Settings → LLM providers → OpenAI. " +
                    "OpenAI Realtime usage is billed separately. Every session stops after 10 minutes; " +
                    "leaving this screen stops the microphone. Headphones are recommended to avoid echo. " +
                    "Android speech recognition and text-to-speech remain available in the normal chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
