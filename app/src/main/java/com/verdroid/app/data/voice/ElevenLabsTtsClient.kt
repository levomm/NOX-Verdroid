package com.verdroid.app.data.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.verdroid.app.data.network.CustomProviderStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ElevenLabsSpeechResult { COMPLETE, BEFORE_AUDIO_FAILURE, AFTER_AUDIO_FAILURE }

/**
 * Streams ElevenLabs PCM directly to AudioTrack (no audio files). All user-initiated
 * cancellation invalidates the callback, stops playback and cancels the HTTP request.
 */
@Singleton
class ElevenLabsTtsClient @Inject constructor(
    private val keys: CustomProviderStore,
    private val prefs: VoicePrefs,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .build() // no logging interceptors: keys and replies must stay out of logs
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var generation = 0L
    private var activeCall: Call? = null
    private var player: AudioTrack? = null

    /** False: no key, invalid configuration or quota blocked, so caller uses Android TTS. */
    fun speak(text: String, done: (ElevenLabsSpeechResult) -> Unit): Boolean {
        val key = keys.keyFor("elevenlabs") ?: return false
        val voiceId = prefs.elevenLabsVoiceId.value
        if (!ElevenLabsTtsPolicy.validVoiceId(voiceId)) return false
        val limited = ElevenLabsTtsPolicy.clipped(text, prefs.replyCharCap.value)
        if (limited.isBlank() || !prefs.reserveElevenLabsChars(limited.length)) return false
        val req = runCatching { ElevenLabsTtsRequest.make(limited, voiceId, key) }.getOrNull()
            ?: return false
        cancel()
        val token: Long
        val call: Call
        synchronized(lock) {
            token = ++generation
            call = http.newCall(req)
            activeCall = call
        }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    completeIfActive(token, done, ElevenLabsSpeechResult.BEFORE_AUDIO_FAILURE)
                }

                override fun onResponse(call: Call, response: Response) {
                    var bytesWritten = 0
                    var output: AudioTrack? = null
                    try {
                        response.use {
                            if (!isActive(token)) return
                            if (!it.isSuccessful) {
                                completeIfActive(token, done, ElevenLabsSpeechResult.BEFORE_AUDIO_FAILURE)
                                return
                            }
                            val stream = it.body?.byteStream()
                                ?: throw IOException("Empty ElevenLabs response")
                            val minBuffer = AudioTrack.getMinBufferSize(
                                24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                            )
                            if (minBuffer <= 0) throw IOException("24kHz PCM not supported")
                            output = AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(24_000)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .setBufferSizeInBytes(maxOf(minBuffer, 16_384))
                                .build()
                            val audio = output!!
                            if (audio.state != AudioTrack.STATE_INITIALIZED) throw IOException("Speaker unavailable")
                            synchronized(lock) {
                                if (token != generation) return
                                player = audio
                            }
                            audio.play()
                            val buffer = ByteArray(4096)
                            while (isActive(token)) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                var offset = 0
                                while (offset < count && isActive(token)) {
                                    val written = audio.write(buffer, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                                    if (written <= 0) throw IOException("Audio output failed")
                                    offset += written
                                    bytesWritten += written
                                }
                            }
                            if (!isActive(token)) return
                            if (bytesWritten == 0) throw IOException("No audio received")
                            // AudioTrack.write queues audio; don't truncate the buffered tail on close.
                            val frames = bytesWritten / 2 // mono 16-bit
                            val deadline = System.currentTimeMillis() + 5_000L
                            while (isActive(token) && audio.playbackHeadPosition.toLong() < frames
                                   && System.currentTimeMillis() < deadline) Thread.sleep(20L)
                            completeIfActive(token, done, ElevenLabsSpeechResult.COMPLETE)
                        }
                    } catch (_: Exception) {
                        completeIfActive(
                            token, done,
                            if (bytesWritten == 0) ElevenLabsSpeechResult.BEFORE_AUDIO_FAILURE
                            else ElevenLabsSpeechResult.AFTER_AUDIO_FAILURE
                        )
                    } finally {
                        synchronized(lock) {
                            if (player === output) player = null
                            if (activeCall === call) activeCall = null
                        }
                        runCatching { output?.pause(); output?.flush(); output?.release() }
                    }
                }
            })
        } catch (_: Exception) {
            cancel()
            return false
        }
        return true
    }

    private fun isActive(token: Long): Boolean = generation == token

    private fun completeIfActive(
        token: Long, done: (ElevenLabsSpeechResult) -> Unit, outcome: ElevenLabsSpeechResult
    ) = main.post {
        if (isActive(token)) done(outcome)
    }

    /** Safe to call from UI thread, voice mic start, screen disposal and ON_STOP. */
    fun cancel() {
        val oldCall: Call?
        val oldPlayer: AudioTrack?
        synchronized(lock) {
            generation++
            oldCall = activeCall; activeCall = null
            oldPlayer = player; player = null
        }
        oldCall?.cancel()
        runCatching { oldPlayer?.pause(); oldPlayer?.flush(); oldPlayer?.release() }
    }
}
