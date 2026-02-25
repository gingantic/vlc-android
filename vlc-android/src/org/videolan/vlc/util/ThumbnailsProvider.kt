package org.videolan.vlc.util


import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.AbstractMedialibrary
import org.videolan.medialibrary.interfaces.AbstractMedialibrary.MEDIALIB_FOLDER_NAME
import org.videolan.medialibrary.interfaces.media.AbstractFolder
import org.videolan.medialibrary.interfaces.media.AbstractMediaWrapper
import org.videolan.medialibrary.interfaces.media.AbstractVideoGroup
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.vlc.VLCApplication
import org.videolan.vlc.gui.helpers.AudioUtil.readCoverBitmap
import org.videolan.vlc.gui.helpers.BitmapCache
import org.videolan.vlc.gui.helpers.BitmapUtil
import org.videolan.vlc.gui.helpers.UiTools
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
object ThumbnailsProvider {

    @Suppress("unused")
    private const val TAG = "VLC/ThumbnailsProvider"

    private var appDir: File? = null
    private var cacheDir: String? = null
    private const val MAX_IMAGES = 4
    private const val MAX_THUMB_HEIGHT = 720 // Max thumbnail height in pixels (480p)

    /**
     * Per-file coroutine Mutexes.
     * Different files can generate thumbnails in parallel; the same file is never decoded twice concurrently.
     */
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Dedicated limited-parallelism dispatcher — max 3 concurrent thumbnail threads.
     * This prevents thumbnail IO from starving the media player's network/disk IO threads.
     * Using Executors.newFixedThreadPool for compatibility with kotlinx-coroutines 1.3.x.
     */
    private val thumbDispatcher = java.util.concurrent.Executors.newFixedThreadPool(3).asCoroutineDispatcher()

    /**
     * Tracks every coroutine [Job] currently generating a thumbnail.
     * [cancelAll] iterates this set and cancels only these specific jobs,
     * leaving the dispatcher and future requests completely unaffected.
     */
    private val activeThumbJobs = java.util.concurrent.CopyOnWriteArraySet<kotlinx.coroutines.Job>()

    /**
     * Cancel every in-flight thumbnail job immediately.
     * Call this right before starting video playback to free hardware codec instances
     * that [MediaMetadataRetriever] may be holding, so VLC can acquire one without stalling.
     */
    fun cancelAll() {
        activeThumbJobs.forEach { it.cancel() }
        activeThumbJobs.clear()
        fileLocks.clear()
    }

    suspend fun getFolderThumbnail(folder: AbstractFolder, width: Int): Bitmap? {
        val media = withContext(thumbDispatcher) {
            folder.media(AbstractFolder.TYPE_FOLDER_VIDEO, AbstractMedialibrary.SORT_DEFAULT, true, 4, 0).filterNotNull()
        }
        return getComposedImage("folder:${folder.title}", media, width)
    }

    suspend fun getVideoGroupThumbnail(group: AbstractVideoGroup, width: Int): Bitmap? {
        val media = withContext(thumbDispatcher) {
            group.media(AbstractMedialibrary.SORT_DEFAULT, true, 4, 0).filterNotNull()
        }
        return getComposedImage("videogroup:${group.title}", media, width)
    }

    suspend fun getMediaThumbnail(item: AbstractMediaWrapper, width: Int): Bitmap? {
        return if (item.type == AbstractMediaWrapper.TYPE_VIDEO && TextUtils.isEmpty(item.artworkMrl))
            getVideoThumbnail(item, width)
        else
            withContext(thumbDispatcher) { readCoverBitmap(Uri.decode(item.artworkMrl), width) }
    }

    private fun getMediaThumbnailPath(isMedia: Boolean, item: MediaLibraryItem): String? {
        if (isMedia && (item as AbstractMediaWrapper).type == AbstractMediaWrapper.TYPE_VIDEO && TextUtils.isEmpty(item.getArtworkMrl())) {
            if (appDir == null) appDir = VLCApplication.appContext.getExternalFilesDir(null)
            val hasCache = appDir != null && appDir!!.exists()
            if (hasCache && cacheDir == null) cacheDir = appDir!!.absolutePath + MEDIALIB_FOLDER_NAME
            return if (hasCache) StringBuilder(cacheDir!!).append('/').append(item.fileName).append(".jpg").toString() else null
        }
        return item.artworkMrl
    }

    fun getMediaCacheKey(isMedia: Boolean, item: MediaLibraryItem, width: String = "") = if (width.isEmpty()) getMediaThumbnailPath(isMedia, item) else "${getMediaThumbnailPath(isMedia, item)}_$width"

    /**
     * Generate or retrieve the thumbnail for a single video file.
     *
     * Parallelism: per-file [Mutex] lets different files decode at the same time.
     *              All work runs on [thumbDispatcher] (max 3 threads).
     *              [cancelAll] cancels individual jobs tracked in [activeThumbJobs].
     */
    suspend fun getVideoThumbnail(media: AbstractMediaWrapper, width: Int): Bitmap? = withContext(thumbDispatcher) {
        // Register this coroutine's Job so cancelAll() can cancel it on demand.
        val currentJob = coroutineContext[kotlinx.coroutines.Job]!!
        activeThumbJobs.add(currentJob)
        try {
            val filePath = media.uri.path ?: return@withContext null
            if (appDir == null) appDir = VLCApplication.appContext.getExternalFilesDir(null)
            val hasCache = appDir?.exists() == true
            val thumbPath = getMediaThumbnailPath(true, media) ?: return@withContext null

            // Fast path: already in memory cache
            val cacheBM = if (hasCache) BitmapCache.getBitmapFromMemCache(getMediaCacheKey(true, media)) else null
            if (cacheBM != null) return@withContext cacheBM

            // Fast path: already saved to disk — read and re-add to memory cache
            if (hasCache && File(thumbPath).exists()) {
                val diskBitmap = readCoverBitmap(thumbPath, width)
                if (diskBitmap != null) BitmapCache.addBitmapToMemCache(thumbPath, diskBitmap)
                return@withContext diskBitmap
            }

            if (media.isThumbnailGenerated) return@withContext null

            // Per-file mutex: only one coroutine generates for any given file at a time.
            val mutex = fileLocks.getOrPut(filePath) { Mutex() }
            val bitmap = try {
                mutex.withLock {
                    // Re-check after acquiring the lock — another coroutine may have finished first.
                    val cached = BitmapCache.getBitmapFromMemCache(getMediaCacheKey(true, media))
                    if (cached != null) return@withLock cached
                    if (hasCache && File(thumbPath).exists()) {
                        val diskBm = readCoverBitmap(thumbPath, width)
                        if (diskBm != null) BitmapCache.addBitmapToMemCache(thumbPath, diskBm)
                        return@withLock diskBm
                    }

                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(filePath)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val timeUs = (duration * 1000L * 0.4).toLong() // 40% of video duration in microseconds
                        // OPTION_PREVIOUS_SYNC: seeks to the nearest keyframe BEFORE the target
                        // time — a single lightweight decode op. OPTION_CLOSEST_SYNC can require
                        // forward-decoding multiple frames and causes 'decode dropped' warnings.
                        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                        retriever.release()
                        // Downscale to max 480p to save memory and disk space
                        if (frame != null && frame.height > MAX_THUMB_HEIGHT) {
                            val scale = MAX_THUMB_HEIGHT.toFloat() / frame.height
                            val scaledWidth = (frame.width * scale).toInt()
                            val scaled = Bitmap.createScaledBitmap(frame, scaledWidth, MAX_THUMB_HEIGHT, true)
                            frame.recycle()
                            scaled
                        } else {
                            frame
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            } finally {
                fileLocks.remove(filePath)
            }

            if (bitmap != null) {
                BitmapCache.addBitmapToMemCache(thumbPath, bitmap)
                if (hasCache) {
                    // Don't call media.setThumbnail() — it fires lastThumb LiveData
                    // which triggers duplicate notifyItemChanged calls and causes
                    // BLASTBufferQueue overflow. Our own disk/memory cache is sufficient.
                    saveOnDisk(bitmap, thumbPath)
                    media.artworkURL = thumbPath
                }
            } else if (media.id != 0L) {
                media.requestThumbnail(width, 0.4f)
            }
            bitmap
        } finally {
            // Always deregister so the set doesn't retain completed/cancelled jobs.
            activeThumbJobs.remove(currentJob)
        }
    }

    suspend fun getPlaylistImage(key: String, mediaList: List<AbstractMediaWrapper>, width: Int) =
            (BitmapCache.getBitmapFromMemCache(key) ?: composePlaylistImage(mediaList, width))?.also {
                BitmapCache.addBitmapToMemCache(key, it)
            }

    /**
     * Compose 1 image from tracks of a Playlist
     * @param mediaList The track list of the playlist
     * @return a Bitmap object
     */
    private suspend fun composePlaylistImage(mediaList: List<AbstractMediaWrapper>, width: Int): Bitmap? {
        if (mediaList.isEmpty()) return null
        val url = mediaList[0].artworkURL
        val isAllSameImage = !mediaList.any { it.artworkURL != url }

        if (isAllSameImage) {

            return obtainBitmap(mediaList[0], width)
        }

        val artworks = ArrayList<AbstractMediaWrapper>()
        for (mediaWrapper in mediaList) {

            val artworkAlreadyHere = artworks.any { it.artworkURL == mediaWrapper.artworkURL }

            if (mediaWrapper.artworkURL != null && mediaWrapper.artworkURL.isNotBlank() && !artworkAlreadyHere) {
                artworks.add(mediaWrapper)
            }
            if (artworks.size > 3) {
                break
            }
        }

        if (artworks.size == 2) {
            artworks.add(artworks[1])
            artworks.add(artworks[0])
        } else if (artworks.size == 3) {
            artworks.add(artworks[0])
        }


        val images = ArrayList<Bitmap>(4)
        artworks.forEach {
            val image = obtainBitmap(it, width / 2)
            if (image != null) {
                images.add(image)
            }
            if (images.size >= 4) {
                return@forEach
            }

        }


        for (i in 0..3) {
            if (images.size < i + 1) {
                images.add(UiTools.getDefaultAudioDrawable(VLCApplication.appContext).bitmap)
            }
        }

        val cs = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        val comboImage = Canvas(cs)
        comboImage.drawBitmap(images[0], Rect(0, 0, images[0].width, images[0].height), Rect(0, 0, width / 2, width / 2), null)
        comboImage.drawBitmap(images[1], Rect(0, 0, images[1].width, images[1].height), Rect(width / 2, 0, width, width / 2), null)
        comboImage.drawBitmap(images[2], Rect(0, 0, images[2].width, images[2].height), Rect(0, width / 2, width / 2, width), null)
        comboImage.drawBitmap(images[3], Rect(0, 0, images[3].width, images[3].height), Rect(width / 2, width / 2, width, width), null)

        return cs
    }

    suspend fun obtainBitmap(item: MediaLibraryItem, width: Int) = withContext(thumbDispatcher) {
        val raw = when (item) {
            is AbstractMediaWrapper -> getMediaThumbnail(item, width)
            is AbstractFolder -> getFolderThumbnail(item, width)
            is AbstractVideoGroup -> getVideoGroupThumbnail(item, width)
            else -> readCoverBitmap(Uri.decode(item.artworkMrl), width)
        }
        // Ensure consistent resolution regardless of which path produced the bitmap.
        downscaleIfNeeded(raw)
    }

    /**
     * Cap a bitmap to [MAX_THUMB_HEIGHT] (preserving aspect ratio).
     * Returns the original bitmap if already small enough, or a new scaled bitmap.
     */
    private fun downscaleIfNeeded(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.height <= MAX_THUMB_HEIGHT) return bitmap
        val scale = MAX_THUMB_HEIGHT.toFloat() / bitmap.height
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, MAX_THUMB_HEIGHT, true)
        bitmap.recycle()
        return scaled
    }

    suspend fun getComposedImage(key: String, mediaList: List<AbstractMediaWrapper>, width: Int): Bitmap? {
        var composedImage = BitmapCache.getBitmapFromMemCache(key)
        if (composedImage == null) {
            composedImage = composeImage(mediaList, width)
            if (composedImage != null) BitmapCache.addBitmapToMemCache(key, composedImage)
        }
        return composedImage
    }

    /**
     * Compose 1 image from combined media thumbnails
     * @param mediaList The media list from which will extract thumbnails
     * @return a Bitmap object
     */
    private suspend fun composeImage(mediaList: List<AbstractMediaWrapper>, imageWidth: Int): Bitmap? {
        val sourcesImages = arrayOfNulls<Bitmap>(min(MAX_IMAGES, mediaList.size))
        var count = 0
        var minWidth = Integer.MAX_VALUE
        var minHeight = Integer.MAX_VALUE
        for (media in mediaList) {
            val bm = getVideoThumbnail(media, imageWidth)
            if (bm != null) {
                val width = bm.width
                val height = bm.height
                sourcesImages[count++] = bm
                minWidth = min(minWidth, width)
                minHeight = min(minHeight, height)
                if (count == MAX_IMAGES) break
            }
        }
        if (count == 0) return null

        return if (count == 1) sourcesImages[0] else composeCanvas(sourcesImages.filterNotNull().toTypedArray(), count, minWidth, minHeight)
    }

    private fun composeCanvas(sourcesImages: Array<Bitmap>, count: Int, minWidth: Int, minHeight: Int): Bitmap {
        val overlayWidth: Int
        val overlayHeight: Int
        when (count) {
            4 -> {
                overlayWidth = 2 * minWidth
                overlayHeight = 2 * minHeight
            }
            else -> {
                overlayWidth = minWidth
                overlayHeight = minHeight
            }
        }
        val bmOverlay = Bitmap.createBitmap(overlayWidth, overlayHeight, sourcesImages[0].config)

        val canvas = Canvas(bmOverlay)
        when (count) {
            2 -> {
                for (i in 0 until count)
                    sourcesImages[i] = BitmapUtil.centerCrop(sourcesImages[i], minWidth / 2, minHeight)
                canvas.drawBitmap(sourcesImages[0], 0f, 0f, null)
                canvas.drawBitmap(sourcesImages[1], (minWidth / 2).toFloat(), 0f, null)
            }
            3 -> {
                sourcesImages[0] = BitmapUtil.centerCrop(sourcesImages[0], minWidth / 2, minHeight / 2)
                sourcesImages[1] = BitmapUtil.centerCrop(sourcesImages[1], minWidth / 2, minHeight / 2)
                sourcesImages[2] = BitmapUtil.centerCrop(sourcesImages[2], minWidth, minHeight / 2)
                canvas.drawBitmap(sourcesImages[0], 0f, 0f, null)
                canvas.drawBitmap(sourcesImages[1], (minWidth / 2).toFloat(), 0f, null)
                canvas.drawBitmap(sourcesImages[2], 0f, (minHeight / 2).toFloat(), null)
            }
            4 -> {
                for (i in 0 until count)
                    sourcesImages[i] = BitmapUtil.centerCrop(sourcesImages[i], minWidth, minHeight)
                canvas.drawBitmap(sourcesImages[0], 0f, 0f, null)
                canvas.drawBitmap(sourcesImages[1], minWidth.toFloat(), 0f, null)
                canvas.drawBitmap(sourcesImages[2], 0f, minHeight.toFloat(), null)
                canvas.drawBitmap(sourcesImages[3], minWidth.toFloat(), minHeight.toFloat(), null)
            }
        }
        return bmOverlay
    }

    private fun saveOnDisk(bitmap: Bitmap, destPath: String) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val byteArray = stream.toByteArray()
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(destPath)
            fos.write(byteArray)
        } catch (e: java.io.IOException) {
            e.printStackTrace()
        } finally {
            Util.close(fos)
            Util.close(stream)
        }
    }
}
