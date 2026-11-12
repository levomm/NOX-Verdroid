package com.verdroid.app.data.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.verdroid.app.data.network.CustomProviderStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ElevenLabsSpeechResult { COMPLETE, BEFORE_AUDIO_FAILURE, AFTER_AUDIO_FAILURE }

/**
 * One paid Eleven v4 Turbo Text-to-Dialogue WebSocket per assistant reply.
 * Audio chunks are PCM16/24k mono and are streamed to AudioTrack without files.
 * Cancellation invalidates callbacks and stops both socket and speaker.
 */
@Singleton
class ElevenLabsTtsClient @Inject constructor(
    private val keys: CustomProviderStore,
    private val prefs: VoicePrefs,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build() // no logging interceptor: neither key nor chat content must be logged
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private class Session {
        val queue = LinkedBlockingQueue<ByteArray>(80)
        @Volatile var socket: WebSocket? = null
        @Volatile var audio: AudioTrack? = null
        @Volatile var finished = false
        @Volatile var failed = false
        @Volatile var worker: Thread? = null
    }

    @Volatile private var active: Session? = null

    /** Returns false for missing key, exhausted local quota or invalid voice; caller uses Android fallback. */
    fun speak(text: String, done: (ElevenLabsSpeechResult) -> Unit): Boolean {
        val key = keys.keyFor("elevenlabs") ?: return false
        val voiceId = prefs.elevenLabsVoiceId.value
        if (!ElevenLabsTtsPolicy.validVoiceId(voiceId)) return false
        val limited = ElevenLabsTtsPolicy.clipped(text, prefs.replyCharCap.value)
        if (limited.isBlank()) return false
        val request: Request = runCatching { ElevenLabsTtsRequest.make(voiceId, key) }.getOrNull()
            ?: return false
        if (!prefs.reserveElevenLabsChars(limited.length)) return false

        cancel()
        val session = Session()
        synchronized(lock) { active = session }

        val socket = try {
            http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (active !== session) { webSocket.cancel(); return }
                    // v4 turbo registers exactly one voice; only Text-to-Dialogue supports this model.
                    val registered = webSocket.send(ElevenLabsTtsRequest.register(voiceId))
                    val submitted = registered && webSocket.send(ElevenLabsTtsRequest.input(limited, voiceId))
                    val flushed = submitted && webSocket.send(ElevenLabsTtsRequest.end())
                    if (!flushed) markFailed(session)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (active !== session) return
                    val packet = ElevenLabsTtsRequest.parse(text)
                    if (packet.isError) {
                        markFailed(session)
                        webSocket.cancel()
                        return
                    }
                    packet.audio?.let { bytes ->
                        if (bytes.isNotEmpty() && !session.queue.offer(bytes, 250, TimeUnit.MILLISECONDS)) {
                            markFailed(session)
                            webSocket.cancel()
                            return
                        }
                    }
                    if (packet.isFinal) session.finished = true
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (active === session) markFailed(session)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (active === session) {
                        if (!session.finished) session.failed = true
                        session.finished = true
                    }
                }
            })
        } catch (_: Exception) {
            synchronized(lock) { if (active === session) active = null }
            return false
        }
        session.socket = socket
        session.worker = Thread({
            playSession(session, done)
        }, "nox-elevenlabs-audio").apply { isDaemon = true; start() }
        return true
    }

    private fun markFailed(session: Session) {
        session.failed = true
        session.finished = true
    }

    private fun playSession(session: Session, done: (ElevenLabsSpeechResult) -> Unit) {
        var bytesWritten = 0
        var output: AudioTrack? = null
        var leftover: Byte? = null
        try {
            while (active === session) {
                val chunk = session.queue.poll(150, TimeUnit.MILLISECONDS)
                if (chunk != null && chunk.isNotEmpty()) {
                    if (output == null) {
                        val minimum = AudioTrack.getMinBufferSize(
                            24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minimum <= 0) throw IllegalStateException("PCM24k unsupported")
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(24_000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(maxOf(minimum, 16_384))
                            .build()
                        if (track.state != AudioTrack.STATE_INITIALIZED) {
                            track.release()
                            throw IllegalStateException("AudioTrack unavailable")
                        }
                        synchronized(lock) {
                            if (active !== session) { track.release(); return }
                            session.audio = track
                        }
                        track.play()
                        output = track
                    }
                    val track = output ?: continue
                    // Preserve 16-bit sample alignment across arbitrarily-sized websocket frames.
                    val packet = if (leftover == null) chunk else byteArrayOf(leftover!!) + chunk
                    val evenSize = packet.size - packet.size % 2
                    leftover = if (evenSize < packet.size) packet.last() else null
                    var offset = 0
                    while (offset < evenSize && active === session) {
                        val count = track.write(packet, offset, evenSize - offset, AudioTrack.WRITE_BLOCKING)
                        if (count <= 0) throw IllegalStateException("AudioTrack write failed")
                        offset += count
                        bytesWritten += count
                    }
                }
                if (session.finished && session.queue.isEmpty()) break
            }

            if (active !== session) return
            if (session.failed || bytesWritten == 0) {
                finish(session, done, if (bytesWritten == 0) ElevenLabsSpeechResult.BEFORE_AUDIO_FAILURE
                    else ElevenLabsSpeechResult.AFTER_AUDIO_FAILURE)
                return
            }
            val track = output ?: throw IllegalStateException("Missing playback")
            val frames = bytesWritten / 2
            val deadline = System.currentTimeMillis() + 5_000L
            while (active === session && track.playbackHeadPosition.toLong() < frames
                && System.currentTimeMillis() < deadline) Thread.sleep(20)
            finish(session, done, ElevenLabsSpeechResult.COMPLETE)
        } catch (_: InterruptedException) {
            // User stopped the voice; never replay or fall back.
        } catch (_: Exception) {
            if (active === session) finish(session, done,
                if (bytesWritten == 0) ElevenLabsSpeechResult.BEFORE_AUDIO_FAILURE
                else ElevenLabsSpeechResult.AFTER_AUDIO_FAILURE)
        } finally {
            synchronized(lock) { if (session.audio === output) session.audio = null }
            runCatching { output?.pause(); output?.flush(); output?.release() }
        }
    }

    private fun finish(
        session: Session, done: (ElevenLabsSpeechResult) -> Unit, result: ElevenLabsSpeechResult
    ) {
        main.post {
            if (active === session) {
                synchronized(lock) { if (active === session) active = null }
                session.socket?.cancel()
                done(result)
            }
        }
    }

    /** Cancel immediately on microphone use, Stop button, background or navigation. */
    fun cancel() {
        val old: Session? = synchronized(lock) {
            val s = active; active = null; s
        }
        old?.socket?.cancel()
        old?.worker?.interrupt()
        val audio = old?.audio
        old?.audio = null
        runCatching { audio?.pause(); audio?.flush(); audio?.release() }
        old?.queue?.clear()
    }
}
