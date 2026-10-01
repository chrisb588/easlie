package com.chrisb588.easlie.images

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Encoded session source; durable assets and manifests belong to the persistence ticket. */
internal data class ImageSource(val file: File, val width: Int, val height: Int, val rotation: Int, val flipped: Boolean) {
    val edge get() = maxOf(width, height)
    suspend fun decode(sample: Int): Bitmap {
        currentCoroutineContext().ensureActive()
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Unreadable image")
        try {
            currentCoroutineContext().ensureActive()
            val matrix = Matrix().apply {
                if (flipped) postScale(-1f, 1f)
                postRotate(rotation.toFloat())
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            try {
                currentCoroutineContext().ensureActive()
                return oriented
            } catch (failure: Exception) {
                oriented.recycle()
                throw failure
            }
        } catch (failure: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw failure
        }
    }
}

internal suspend fun copyImageSource(resolver: ContentResolver, uri: Uri, directory: File?): ImageSource {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Unsupported image source" }
    require(resolver.getType(uri).let { it == null || it in supportedMimeTypes }) { "Unsupported image type" }
    val file = File.createTempFile("reference-", ".image", directory)
    try {
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Unreadable image")
        return readImageSource(file)
    } catch (failure: Exception) {
        file.delete()
        throw failure
    }
}

/** Read full oriented dimensions while validating only a small bitmap. Called on IO. */
internal suspend fun readImageSource(file: File): ImageSource {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in supportedMimeTypes)
    file.inputStream().use { requireStillImage(it, bounds.outMimeType) }
    val exif = ExifInterface(file)
    val swapped = exif.rotationDegrees % 180 != 0
    val source = ImageSource(file, if (swapped) bounds.outHeight else bounds.outWidth,
        if (swapped) bounds.outWidth else bounds.outHeight, exif.rotationDegrees, exif.isFlipped)
    // Metadata alone can survive truncated pixel data. Validate a tiny decode before accepting.
    var sample = 1
    while (source.edge / sample > 128) sample *= 2
    source.decode(sample).recycle()
    return source
}
