package com.teja.bumblebee.ui.diagnostics

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory event log for the Diagnostics screen (keys, media buttons, broadcasts, audio focus). */
object KeyLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        lines.addFirst("${time.format(Date())}  $message")
        while (lines.size > MAX) lines.removeLast()
        main.post { listeners.forEach { it() } }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    fun listen(listener: () -> Unit) { listeners += listener }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
}
