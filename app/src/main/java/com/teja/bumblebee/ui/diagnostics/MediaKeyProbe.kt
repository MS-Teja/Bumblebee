package com.teja.bumblebee.ui.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Pretends to be a playing music app (silent audio + active MediaSession) so the head unit routes
 * steering-wheel and panel keys to us, then logs which delivery path they arrive on.
 */
class MediaKeyProbe(private val context: Context) {

    private var session: MediaSession? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val name = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT (call / reverse cam?)"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_CAN_DUCK (navigation prompt?)"
            else -> change.toString()
        }
        KeyLog.add("Audio focus: $name")
    }

    private val broadcasts = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val extras = intent.extras?.keySet()?.joinToString { k -> "$k=${intent.extras?.get(k)}" }.orEmpty()
            KeyLog.add("Broadcast: ${intent.action} $extras")
        }
    }

    val isRunning get() = session != null

    fun start() {
        if (isRunning) return
        KeyLog.add("Key test started: press steering-wheel and panel buttons now")

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            KeyLog.add("Audio focus request: ${audio.requestAudioFocus(focusRequest!!)}")
        } else {
            @Suppress("DEPRECATION")
            KeyLog.add("Audio focus request: ${audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)}")
        }

        // One second of silence, looped forever, so the system sees an actively playing media app.
        val rate = 44100
        val frames = rate
        track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(frames * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build().apply {
                write(ShortArray(frames), 0, frames)
                setLoopPoints(0, frames, -1)
                play()
            }

        session = MediaSession(context, "BumblebeeKeyProbe").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val key = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (key != null && key.action == KeyEvent.ACTION_DOWN) {
                        KeyLog.add("MediaSession button: ${KeyEvent.keyCodeToString(key.keyCode)} (repeat ${key.repeatCount})")
                    }
                    return true
                }
                override fun onPlay() = KeyLog.add("MediaSession: onPlay")
                override fun onPause() = KeyLog.add("MediaSession: onPause")
                override fun onSkipToNext() = KeyLog.add("MediaSession: onSkipToNext")
                override fun onSkipToPrevious() = KeyLog.add("MediaSession: onSkipToPrevious")
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
            isActive = true
        }

        val filter = IntentFilter().apply {
            listOf(
                Intent.ACTION_MEDIA_BUTTON,
                "com.android.music.musicservicecommand",
                "com.android.music.togglepause",
                "com.android.music.pause",
                "com.android.music.next",
                "com.android.music.previous",
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SHUTDOWN,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                AudioManager.ACTION_AUDIO_BECOMING_NOISY,
            ).forEach(::addAction)
        }
        ContextCompat.registerReceiver(context, broadcasts, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        if (!isRunning) return
        runCatching { context.unregisterReceiver(broadcasts) }
        session?.run { isActive = false; release() }
        session = null
        track?.run { stop(); release() }
        track = null
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let(audio::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(focusListener)
        }
        KeyLog.add("Key test stopped")
    }
}
