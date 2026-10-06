package com.teja.bumblebee.ui.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.view.WindowManager
import java.io.File

/** Collects the real facts about the head unit that the Settings screen may be spoofing. */
object DeviceReport {

    enum class Level { INFO, OK, WARN, BAD }

    data class Row(val key: String, val value: String, val level: Level = Level.INFO)
    data class Section(val title: String, val rows: List<Row>)

    fun collect(ctx: Context): List<Section> = listOf(
        system(),
        memory(ctx),
        display(ctx),
        cpu(),
        storage(ctx),
        decoders(),
        packages(ctx),
    )

    fun asText(sections: List<Section>, log: List<String>): String = buildString {
        appendLine("Bumblebee — Under the hood")
        appendLine("Generated: ${java.util.Date()}")
        sections.forEach { s ->
            appendLine()
            appendLine("== ${s.title} ==")
            s.rows.forEach { appendLine("${it.key}: ${it.value}") }
        }
        appendLine()
        appendLine("== Event log (newest first) ==")
        log.forEach { appendLine(it) }
    }

    private fun system(): Section {
        val sdk = Build.VERSION.SDK_INT
        val lowRam = prop("ro.config.low_ram")
        return Section(
            "System",
            listOf(
                Row("Real Android version", "${Build.VERSION.RELEASE} (API $sdk)", if (sdk >= 29) Level.OK else Level.WARN),
                Row("ro.build.version.release", prop("ro.build.version.release")),
                Row("Go / low-RAM build", lowRam.ifBlank { "false" }, if (lowRam == "true") Level.WARN else Level.OK),
                Row("Manufacturer / model", "${Build.MANUFACTURER} / ${Build.MODEL}"),
                Row("Board / hardware", "${Build.BOARD} / ${Build.HARDWARE}"),
                Row("Platform", prop("ro.board.platform")),
                Row("ABIs", Build.SUPPORTED_ABIS.joinToString()),
                Row("Fingerprint", Build.FINGERPRINT),
            ),
        )
    }

    private fun memory(ctx: Context): Section {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = info.totalMem / (1024 * 1024)
        return Section(
            "Memory",
            listOf(
                Row("Real RAM", "$totalMb MB", if (totalMb >= 1800) Level.OK else Level.WARN),
                Row("Available now", "${info.availMem / (1024 * 1024)} MB"),
                Row("isLowRamDevice", am.isLowRamDevice.toString(), if (am.isLowRamDevice) Level.WARN else Level.OK),
                Row("Per-app heap (memoryClass)", "${am.memoryClass} MB"),
            ),
        )
    }

    private fun display(ctx: Context): Section {
        val m = ctx.resources.displayMetrics
        val c = ctx.resources.configuration
        @Suppress("DEPRECATION")
        val refresh = ctx.getSystemService(WindowManager::class.java).defaultDisplay.refreshRate
        return Section(
            "Display",
            listOf(
                Row("Pixels", "${m.widthPixels} × ${m.heightPixels}"),
                Row("Density", "${m.densityDpi} dpi (×${m.density})"),
                Row("Size in dp", "${c.screenWidthDp} × ${c.screenHeightDp} dp", if (c.screenHeightDp < 480) Level.WARN else Level.OK),
                Row("Smallest width", "${c.smallestScreenWidthDp} dp"),
                Row("Font scale", c.fontScale.toString()),
                Row("Refresh rate", "%.0f Hz".format(refresh)),
                Row("ro.sf.lcd_density", prop("ro.sf.lcd_density")),
            ),
        )
    }

    private fun cpu(): Section {
        val cpuinfo = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
        fun field(name: String) = cpuinfo.firstOrNull { it.startsWith(name) }?.substringAfter(":")?.trim().orEmpty()
        val maxFreq = runCatching {
            File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000
        }.getOrNull()
        return Section(
            "CPU",
            listOf(
                Row("Cores", Runtime.getRuntime().availableProcessors().toString()),
                Row("Max clock", maxFreq?.let { "$it MHz" } ?: "unknown"),
                Row("Hardware", field("Hardware").ifBlank { Build.HARDWARE }),
                Row("Model", field("model name").ifBlank { field("Processor") }),
                Row("CPU part", field("CPU part")),
            ),
        )
    }

    private fun storage(ctx: Context): Section {
        val rows = mutableListOf<Row>()
        val access = when {
            Build.VERSION.SDK_INT >= 30 -> if (Environment.isExternalStorageManager()) "All files access" else "NOT granted"
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED -> "Read storage"
            else -> "NOT granted"
        }
        rows += Row("Storage access", access, if (access == "NOT granted") Level.BAD else Level.OK)

        val sm = ctx.getSystemService(StorageManager::class.java)
        sm.storageVolumes.forEachIndexed { i, v ->
            val path = volumePath(v)
            val space = path?.let { runCatching { StatFs(it.path) }.getOrNull() }
            val capacity = space?.let { "%.1f of %.1f GB free".format(it.availableBytes / 1e9, it.totalBytes / 1e9) } ?: "?"
            rows += Row(
                "Volume ${i + 1}: ${v.getDescription(ctx)}",
                "${path ?: "no path"} · ${v.state} · ${if (v.isRemovable) "removable" else "built-in"}" +
                    "${if (v.isPrimary) " · primary" else ""} · uuid=${v.uuid ?: "-"} · $capacity",
            )
        }
        val probes = listOf("/storage", "/mnt", "/mnt/media_rw", "/mnt/usb_storage", "/mnt/usbhost", "/mnt/udisk", "/mnt/usb", "/udisk")
        probes.forEach { p ->
            val f = File(p)
            if (f.exists()) rows += Row("ls $p", f.list()?.sorted()?.joinToString().orEmpty().ifBlank { "(empty or unreadable)" })
        }
        return Section("Storage & USB", rows)
    }

    fun volumePath(v: StorageVolume): File? =
        if (Build.VERSION.SDK_INT >= 30) {
            v.directory
        } else {
            runCatching { v.javaClass.getMethod("getPath").invoke(v) as String }.getOrNull()?.let(::File)
        }

    private fun decoders(): Section {
        val mimes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .filter { it.startsWith("audio/") }
            .toSortedSet()
        fun has(vararg m: String) = m.any { it in mimes }
        return Section(
            "Audio decoders",
            listOf(
                Row("MP3", has("audio/mpeg").yes(), if (has("audio/mpeg")) Level.OK else Level.BAD),
                Row("AAC / M4A", has("audio/mp4a-latm").yes(), if (has("audio/mp4a-latm")) Level.OK else Level.BAD),
                Row("FLAC", has("audio/flac").yes() + " (ExoPlayer also has its own extractor)"),
                Row("Vorbis / Opus", "${has("audio/vorbis").yes()} / ${has("audio/opus").yes()}"),
                Row("WMA", has("audio/x-ms-wma", "audio/wma").yes(), if (has("audio/x-ms-wma", "audio/wma")) Level.OK else Level.WARN),
                Row("ALAC", has("audio/alac").yes(), if (has("audio/alac")) Level.OK else Level.WARN),
                Row("All audio types", mimes.joinToString()),
            ),
        )
    }

    private fun packages(ctx: Context): Section {
        val pm = ctx.packageManager
        val rows = mutableListOf<Row>()
        fun handler(intent: Intent) = pm.queryIntentActivities(intent, 0).joinToString { it.activityInfo.packageName }.ifBlank { "none" }
        rows += Row("Home launcher(s)", handler(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        rows += Row("APP_MUSIC handlers", handler(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)))
        rows += Row(
            "Legacy music-command receivers",
            pm.queryBroadcastReceivers(Intent("com.android.music.musicservicecommand"), 0)
                .joinToString { it.activityInfo.packageName }.ifBlank { "none" },
        )
        val keywords = listOf("music", "media", "audio", "bt", "blue", "car", "launcher", "zlink", "carbit", "autokit", "tlink", "carplay", "radio", "canbus", "mcu", "syu", "fyt", "ts.")
        @Suppress("DEPRECATION")
        val matches = pm.getInstalledPackages(0)
            .map { it.packageName }
            .filter { name -> keywords.any { name.contains(it, ignoreCase = true) } }
            .filterNot { it.startsWith("com.android.providers") || it == ctx.packageName }
            .sorted()
        rows += Row("Interesting packages (${matches.size})", matches.joinToString("\n"))
        return Section("Head unit apps", rows)
    }

    private fun Boolean.yes() = if (this) "yes" else "no"

    private fun prop(key: String): String = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", key)).inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrDefault("")
}
