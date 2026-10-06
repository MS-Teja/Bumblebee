package com.teja.bumblebee.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import com.teja.bumblebee.data.Prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class Volume(
    val id: String,
    val label: String,
    val root: File,
    val removable: Boolean,
    val primary: Boolean,
) {
    val path: String get() = root.path
    val mixtape: Int get() = Prefs.mixtapeNumber(id, primary)
    fun space(): Pair<Long, Long>? = runCatching { StatFs(path).let { it.totalBytes - it.availableBytes to it.totalBytes } }.getOrNull()
}

sealed class VolumeEvent {
    data class Mounted(val volume: Volume) : VolumeEvent()
    data class Removed(val volume: Volume) : VolumeEvent()
}

/** Tracks mounted storage: internal, SD and USB drives (including head units' odd mount points). */
object Volumes {
    private val _all = MutableStateFlow<List<Volume>>(emptyList())
    val all: StateFlow<List<Volume>> = _all
    private val _events = MutableSharedFlow<VolumeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<VolumeEvent> = _events
    private var started = false
    private lateinit var app: Context

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        refresh()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = refresh()
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun forPath(path: String): Volume? = _all.value.filter { path.startsWith(it.path + "/") || path == it.path }.maxByOrNull { it.path.length }

    /** Best guess of the volume a missing file lived on (for "Insert SanDisk to continue"). */
    fun labelForMissing(path: String): String {
        forPath(path)?.let { return it.label }
        val uuid = path.removePrefix("/storage/").substringBefore('/')
        return Prefs.raw().getString("vol_label_$uuid", null) ?: "your USB drive"
    }

    @Synchronized
    fun refresh() {
        val found = scan()
        val before = _all.value.associateBy { it.id }
        val after = found.associateBy { it.id }
        _all.value = found
        found.forEach { Prefs.raw().edit().putString("vol_label_${it.id}", it.label).apply() }
        after.keys.minus(before.keys).forEach { _events.tryEmit(VolumeEvent.Mounted(after.getValue(it))) }
        before.keys.minus(after.keys).forEach { _events.tryEmit(VolumeEvent.Removed(before.getValue(it))) }
    }

    private fun scan(): List<Volume> {
        val out = LinkedHashMap<String, Volume>()
        val sm = app.getSystemService(StorageManager::class.java)
        sm.storageVolumes.forEach { v ->
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
            val dir = pathOf(v) ?: return@forEach
            if (!dir.canRead() && !v.isPrimary) return@forEach
            val id = if (v.isPrimary) "primary" else v.uuid ?: dir.name
            val label = when {
                v.isPrimary -> "Internal"
                else -> v.getDescription(app)?.takeIf { it.isNotBlank() } ?: "USB drive"
            }
            out[dir.path] = Volume(id, label, dir, v.isRemovable, v.isPrimary)
        }
        // Head units sometimes mount USB outside StorageManager's knowledge.
        listOf("/mnt/usb_storage", "/mnt/usbhost", "/mnt/udisk", "/mnt/usb", "/udisk", "/mnt/media_rw").forEach { base ->
            val f = File(base)
            val candidates = if (f.isDirectory) (f.listFiles()?.filter { it.isDirectory }.orEmpty() + f) else emptyList()
            candidates.filter { it.canRead() && (it.list()?.isNotEmpty() == true) && it.path !in out }.forEach { dir ->
                if (out.values.none { dir.path.startsWith(it.path) }) {
                    out[dir.path] = Volume(dir.name, "USB drive", dir, removable = true, primary = false)
                }
            }
        }
        return out.values.sortedWith(compareBy({ !it.primary }, { it.label }))
    }

    fun pathOf(v: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= 30) {
            v.directory
        } else {
            runCatching { v.javaClass.getMethod("getPath").invoke(v) as String }.getOrNull()?.let(::File)
        }
}
