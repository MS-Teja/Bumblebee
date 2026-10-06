package com.teja.bumblebee.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.widget.ImageView
import com.teja.bumblebee.R
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.data.Track
import com.teja.bumblebee.ui.design.Accent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover art: folder image → embedded picture → designed placeholder. Two sizes, memory + disk caches,
 * at most two decodes at a time so scrolling stays smooth on four slow cores.
 */
object ArtLoader {

    enum class Size(val px: Int, val config: Bitmap.Config) { SMALL(192, Bitmap.Config.RGB_565), LARGE(600, Bitmap.Config.ARGB_8888) }

    private lateinit var dir: File
    private val memory = object : LruCache<String, Bitmap>(14 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val accents = ConcurrentHashMap<String, Accent>()
    private val folderImages = ConcurrentHashMap<String, String>()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        dir = File(context.cacheDir, "art").apply { mkdirs() }
    }

    fun placeholder(track: Track): Drawable = GeneratedCover(track.album ?: track.title, track.album, track.album ?: track.parent)

    fun cached(track: Track, size: Size): Bitmap? = memory.get(track.artKey + "|" + size.name)

    /** Real cover art, or null when the file has none. */
    suspend fun art(track: Track, size: Size): Bitmap? {
        val key = track.artKey
        memory.get("$key|${size.name}")?.let { return it }
        if (key in missing) return null
        return withContext(io) {
            memory.get("$key|${size.name}")?.let { return@withContext it }
            val file = File(dir, hash(key) + "_" + size.name + ".jpg")
            val none = File(dir, hash(key) + ".none")
            if (none.exists()) { missing += key; return@withContext null }
            val fromDisk = if (file.exists()) BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = size.config }) else null
            val bmp = fromDisk ?: extract(track, size)?.also { b ->
                runCatching { file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
            }
            if (bmp == null) {
                runCatching { none.createNewFile() }
                missing += key
            } else {
                memory.put("$key|${size.name}", bmp)
            }
            bmp
        }
    }

    /** Colours for a track: from real art when present, otherwise from its generated cover. */
    suspend fun accent(track: Track): Accent {
        accents[track.artKey]?.let { return it }
        val bmp = art(track, Size.SMALL)
        val a = withContext(io) { if (bmp != null) AccentEngine.fromBitmap(bmp) else CoverPalette.accentFor(track.album ?: track.parent) }
        accents[track.artKey] = a
        return a
    }

    fun cachedAccent(track: Track): Accent? = accents[track.artKey]

    /** Real art or the rendered placeholder, always a bitmap (used by the flying-cover transition). */
    suspend fun bitmapOrPlaceholder(track: Track, size: Size): Bitmap =
        art(track, size) ?: withContext(io) { Rasterize.of(placeholder(track), size.px / 2) }

    /** Shows the placeholder instantly, then crossfades to the real art if there is any. */
    fun bind(view: ImageView, track: Track?, size: Size, fade: Boolean = true) {
        (view.getTag(R.id.art_job) as? Job)?.cancel()
        if (track == null) {
            view.setImageDrawable(null)
            view.setTag(R.id.art_key, null)
            return
        }
        val key = track.artKey + "|" + size.name
        if (view.getTag(R.id.art_key) == key && view.drawable != null) return
        view.setTag(R.id.art_key, key)
        memory.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        val ph = placeholder(track)
        view.setImageDrawable(ph)
        if (track.artKey in missing) return
        view.setTag(R.id.art_job, scope.launch {
            val bmp = art(track, size) ?: return@launch
            if (view.getTag(R.id.art_key) != key) return@launch
            if (fade) {
                val t = TransitionDrawable(arrayOf(ph, BitmapDrawable(view.resources, bmp))).apply { isCrossFadeEnabled = true }
                view.setImageDrawable(t)
                t.startTransition(220)
            } else {
                view.setImageBitmap(bmp)
            }
        })
    }

    /** Folder art: a mosaic of its most common albums, or a cassette label when it has no art at all. */
    fun bindFolder(view: ImageView, path: String, label: String, size: Size) {
        (view.getTag(R.id.art_job) as? Job)?.cancel()
        val key = "folder:$path|${size.name}"
        if (view.getTag(R.id.art_key) == key && view.drawable != null) return
        view.setTag(R.id.art_key, key)
        memory.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(CassetteCover(label, path))
        view.setTag(R.id.art_job, scope.launch {
            val bmp = folderMosaic(path, label, size) ?: return@launch
            if (view.getTag(R.id.art_key) != key) return@launch
            view.setImageBitmap(bmp)
            view.alpha = 0.4f
            view.animate().alpha(1f).setDuration(180).start()
        })
    }

    private suspend fun folderMosaic(path: String, label: String, size: Size): Bitmap? {
        val tracks = withContext(io) { Library.mosaicTracks(path, 10) }
        if (tracks.isEmpty()) return null
        val loaded = tracks.map { t -> t to art(t, Size.SMALL) }
        val real = loaded.mapNotNull { it.second }
        if (real.isEmpty() && tracks.map { it.album }.distinct().size <= 1) return null
        // Real covers first; generated ones only fill the gaps.
        val tiles: List<Any> = (real + loaded.filter { it.second == null }.map { placeholder(it.first) }).take(4)
        val bmp = withContext(io) { Mosaic.compose(tiles, if (size == Size.LARGE) 480 else 240) }
        memory.put("folder:$path|${size.name}", bmp)
        return bmp
    }

    /** First track's colours for a folder hero. */
    suspend fun folderAccent(path: String): Accent? {
        val t = withContext(io) { Library.mosaicTracks(path, 1).firstOrNull() } ?: return null
        return accent(t)
    }

    // ------------------------------------------------------------------ extraction

    private fun extract(track: Track, size: Size): Bitmap? {
        val folderFirst = Prefs.preferFolderArt
        if (folderFirst) folderImage(track.parent)?.let { decodeFile(it, size)?.let { b -> return b } }
        embedded(track.path, size)?.let { return it }
        if (!folderFirst) folderImage(track.parent)?.let { decodeFile(it, size)?.let { b -> return b } }
        return null
    }

    private val coverNames = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall", "albumart_large")

    private fun folderImage(dirPath: String): String? {
        folderImages[dirPath]?.let { return it.ifEmpty { null } }
        val found = File(dirPath).listFiles()?.firstOrNull { f ->
            val n = f.name.lowercase()
            val stem = n.substringBeforeLast('.')
            val ext = n.substringAfterLast('.', "")
            ext in setOf("jpg", "jpeg", "png", "webp") && (stem in coverNames || stem.startsWith("albumart"))
        }?.path
        folderImages[dirPath] = found ?: ""
        return found
    }

    private fun embedded(path: String, size: Size): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.embeddedPicture?.let { decodeBytes(it, size) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun decodeFile(path: String, size: Size): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds, size); inPreferredConfig = size.config }
        return BitmapFactory.decodeFile(path, opts)?.let { square(it, size) }
    }

    private fun decodeBytes(bytes: ByteArray, size: Size): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds, size); inPreferredConfig = size.config }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { square(it, size) }
    }

    private fun sample(o: BitmapFactory.Options, size: Size): Int {
        var s = 1
        while (o.outWidth / (s * 2) >= size.px && o.outHeight / (s * 2) >= size.px) s *= 2
        return s
    }

    private fun square(b: Bitmap, size: Size): Bitmap {
        val side = minOf(b.width, b.height)
        val cropped = if (b.width != b.height) Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side) else b
        return if (side > size.px) Bitmap.createScaledBitmap(cropped, size.px, size.px, true) else cropped
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
