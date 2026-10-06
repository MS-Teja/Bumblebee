package com.teja.bumblebee.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** All user settings plus small bits of persisted state. One SharedPreferences file. */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.getSharedPreferences("bee", Context.MODE_PRIVATE)
    }

    private fun bump() { _changes.value++ }

    // --- Layout ---
    var driverRight: Boolean
        get() = sp.getBoolean("driver_right", true)
        set(v) { sp.edit().putBoolean("driver_right", v).apply(); bump() }
    var immersive: Boolean
        get() = sp.getBoolean("immersive", true)
        set(v) { sp.edit().putBoolean("immersive", v).apply(); bump() }
    /** 0 = off. */
    var glanceDelaySec: Int
        get() = sp.getInt("glance_delay", 20)
        set(v) { sp.edit().putInt("glance_delay", v).apply(); bump() }
    var reduceMotion: Boolean
        get() = sp.getBoolean("reduce_motion", false)
        set(v) { sp.edit().putBoolean("reduce_motion", v).apply(); bump() }

    // --- Playback ---
    /** "play", "paused" or "off". */
    var onStartup: String
        get() = sp.getString("on_startup", "play")!!
        set(v) { sp.edit().putString("on_startup", v).apply(); bump() }
    /** "ask", "play" or "none". */
    var onUsb: String
        get() = sp.getString("on_usb", "ask")!!
        set(v) { sp.edit().putString("on_usb", v).apply(); bump() }
    var tapIncludesSubfolders: Boolean
        get() = sp.getBoolean("tap_subfolders", false)
        set(v) { sp.edit().putBoolean("tap_subfolders", v).apply(); bump() }

    // --- Library ---
    var minDurationSec: Int
        get() = sp.getInt("min_duration", 30)
        set(v) { sp.edit().putInt("min_duration", v).apply(); bump() }
    var respectNomedia: Boolean
        get() = sp.getBoolean("nomedia", true)
        set(v) { sp.edit().putBoolean("nomedia", v).apply(); bump() }
    var preferFolderArt: Boolean
        get() = sp.getBoolean("folder_art", true)
        set(v) { sp.edit().putBoolean("folder_art", v).apply(); bump() }
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("excluded", v).apply(); bump() }
    var scannedVolumes: Set<String>
        get() = sp.getStringSet("scanned_volumes", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("scanned_volumes", v).apply() }
    var lastScanAt: Long
        get() = sp.getLong("last_scan", 0)
        set(v) { sp.edit().putLong("last_scan", v).apply() }

    // --- Fun ---
    var easterEggs: Boolean
        get() = sp.getBoolean("eggs", true)
        set(v) { sp.edit().putBoolean("eggs", v).apply(); bump() }
    var camaro: Boolean
        get() = sp.getBoolean("camaro", false)
        set(v) { sp.edit().putBoolean("camaro", v).apply(); bump() }
    var listenedMs: Long
        get() = sp.getLong("listened_ms", 0)
        set(v) { sp.edit().putLong("listened_ms", v).apply() }

    // --- Onboarding / hints ---
    var swipeHintShown: Boolean
        get() = sp.getBoolean("swipe_hint", false)
        set(v) { sp.edit().putBoolean("swipe_hint", v).apply() }

    /** Volume uuid -> "Mixtape Vol. N" number; internal storage is always 1. */
    fun mixtapeNumber(volumeId: String, primary: Boolean): Int {
        if (primary) return 1
        val key = "vol_no_$volumeId"
        val existing = sp.getInt(key, 0)
        if (existing > 0) return existing
        val next = sp.getInt("vol_no_next", 2)
        sp.edit().putInt(key, next).putInt("vol_no_next", next + 1).apply()
        return next
    }

    /** True at most once per day for [id]; used by easter eggs. */
    fun oncePerDay(id: String): Boolean {
        val today = System.currentTimeMillis() / 86_400_000L
        val key = "egg_day_$id"
        if (sp.getLong(key, -1) == today) return false
        sp.edit().putLong(key, today).apply()
        return true
    }

    fun raw(): SharedPreferences = sp
}
