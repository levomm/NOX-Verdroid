package com.verdroid.app.ui.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.network.CustomProviderStore
import com.verdroid.app.data.voice.ElevenLabsTtsClient
import com.verdroid.app.data.voice.ElevenLabsTtsPolicy
import com.verdroid.app.data.voice.VoicePrefs
import com.verdroid.app.ui.components.SettingsGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    val prefs: VoicePrefs,
    val keyStore: CustomProviderStore,
    private val elevenLabs: ElevenLabsTtsClient,
) : ViewModel() {
    fun previewVoice(): Boolean = elevenLabs.speak(
        "Tere. Mina olen NOX. Kas me saame nüüd lõpuks asja tööle või tuleb jälle logisid lugeda?"
    ) { }
    fun stopPreview() = elevenLabs.cancel()
    override fun onCleared() { elevenLabs.cancel(); super.onCleared() }
}

/** Settings → Voice: normal-chat Android / ElevenLabs TTS plus separate Realtime voice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit, onRealtime: () -> Unit = {}, vm: VoiceSettingsViewModel = hiltViewModel()) {
    val p = vm.prefs
    val mic by p.micButton.collectAsStateWithLifecycle()
    val autoSend by p.autoSend.collectAsStateWithLifecycle()
    val speak by p.speakReplies.collectAsStateWithLifecycle()
    val handsFree by p.handsFree.collectAsStateWithLifecycle()
    val provider by p.ttsProvider.collectAsStateWithLifecycle()
    val voiceId by p.elevenLabsVoiceId.collectAsStateWithLifecycle()
    val replyCap by p.replyCharCap.collectAsStateWithLifecycle()
    val dailyCap by p.dailyCharCap.collectAsStateWithLifecycle()
    val keyVersion by vm.keyStore.version.collectAsStateWithLifecycle()
    val configured = remember(keyVersion) { vm.keyStore.keyFor("elevenlabs") != null }
    val ctx = LocalContext.current
    val recognizer = remember { runCatching { android.speech.SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false) }
    var enteredKey by remember { mutableStateOf("") }
    var customVoice by remember(voiceId) { mutableStateOf(voiceId) }
    var previewStatus by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopPreview() }
    DisposableEffect(vm) { onDispose { vm.stopPreview() } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.voice)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            @Composable fun toggleRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
                ListItem(
                    headlineContent = { Text(title) }, supportingContent = { Text(sub) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    trailingContent = { Switch(checked = checked, onCheckedChange = set, enabled = enabled) }
                )
            }
            SettingsGroup(stringResource(R.string.group_stt), listOf(
                { _ -> toggleRow(stringResource(R.string.voice_mic), stringResource(R.string.voice_mic_desc), mic, set = p::setMicButton) },
                { _ -> toggleRow(stringResource(R.string.voice_auto_send), stringResource(R.string.voice_auto_send_desc), autoSend, set = p::setAutoSend) }
            ))
            SettingsGroup(stringResource(R.string.group_tts), listOf(
                { _ -> toggleRow(stringResource(R.string.voice_speak), stringResource(R.string.voice_speak_desc), speak, set = p::setSpeakReplies) },
                { _ -> toggleRow(stringResource(R.string.voice_hands_free), stringResource(R.string.voice_hands_free_desc), handsFree, enabled = speak, set = p::setHandsFree) }
            ))
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Tavavestluse hääl", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            listOf(
                "elevenlabs" to "ElevenLabs · loomulik naishääl",
                "android" to "Android · süsteemi kõnesüntees"
            ).forEach { (id, label) ->
                ListItem(
                    headlineContent = { Text(label) },
                    trailingContent = { RadioButton(selected = provider == id, onClick = { p.setTtsProvider(id) }) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                )
            }
            Text(
                if (configured) "ElevenLabsi API-võti on turvaliselt salvestatud."
                else "ElevenLabsi API-võti puudub. Seni räägib NOX Androidi häälega.",
                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = enteredKey, onValueChange = { enteredKey = it },
                label = { Text("ElevenLabs API-võti") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.keyStore.save("elevenlabs", "", "", enteredKey)
                    enteredKey = ""
                }, enabled = enteredKey.isNotBlank()) { Text("Salvesta võti") }
                OutlinedButton(onClick = { vm.keyStore.save("elevenlabs", "", "", null) }, enabled = configured) {
                    Text("Kustuta võti")
                }
            }
            Text("Naiskõne hääl: Bri (rahulik, selge, Soli-laadne tempo). Eesti hääldus vajab kontrollimist.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = voiceId == ElevenLabsTtsPolicy.DEFAULT_VOICE_ID,
                    onClick = { p.setElevenLabsVoiceId(ElevenLabsTtsPolicy.DEFAULT_VOICE_ID) },
                    label = { Text("Bri") })
                FilterChip(selected = voiceId == "cuTixVvCOWFxeH3oGE9m",
                    onClick = { p.setElevenLabsVoiceId("cuTixVvCOWFxeH3oGE9m") },
                    label = { Text("Clara") })
            }
            OutlinedTextField(
                value = customVoice, onValueChange = { customVoice = it.trim() },
                label = { Text("Või sisesta enda ElevenLabs Voice ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
            Button(
                onClick = { p.setElevenLabsVoiceId(customVoice) },
                enabled = ElevenLabsTtsPolicy.validVoiceId(customVoice) && customVoice != voiceId,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            ) { Text("Salvesta hääl") }
            Text("Krediidipiirang: ühe vastuse pikkus", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(600, 1200, 2400).forEach { n ->
                    FilterChip(selected = replyCap == n, onClick = { p.setReplyCharCap(n) },
                        label = { Text("$n märki") })
                }
            }
            Text("Päevane ElevenLabsi tekstilimiit (kohalik turvapiirang)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2000, 8000, 20000).forEach { n ->
                    FilterChip(selected = dailyCap == n, onClick = { p.setDailyCharCap(n) },
                        label = { Text("$n") })
                }
            }
            Text(
                "Täna kasutatud: ${p.usedElevenLabsCharsToday()} / $dailyCap märki. " +
                    "See piirab NOXi päringuid, mitte ElevenLabsi konto üldkulu. " +
                    "Vastuse tekst saadetakse kõnesünteesiks ElevenLabsi serverisse; kasutatakse sinu API-krediiti.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    previewStatus = if (vm.previewVoice()) "Hääletest käivitatud (tasuline)." else "Hääletest pole saadaval: kontrolli võtit ja limiiti."
                }, enabled = configured) { Text("Kuula näidist (tasuline)") }
                OutlinedButton(onClick = { vm.stopPreview() }) { Text("Peata") }
            }
            if (previewStatus.isNotBlank()) Text(previewStatus,
                modifier = Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("OpenAI Realtime Voice", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp))
            Text("Eraldi tasuline häälvestlus. NOXi tavavestluse ElevenLabsi hääl töötab ülal valitud mootoriga.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Button(onClick = onRealtime, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text("Open NOX Realtime Voice")
            }
            Text(
                "Kõne tuvastus: ${if (recognizer) "olemas" else "kasutatakse süsteemi kõneakent"}. " +
                    "Telefoni keel: ${Locale.getDefault().displayName}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }
}
