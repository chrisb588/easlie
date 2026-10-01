package com.chrisb588.easlie.images

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface

internal val supportedMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif", "image/bmp")

/** Only URI payloads from image share actions are accepted; text and arbitrary actions are ignored. */
fun sharedImageUris(intent: Intent): List<Uri> {
    if (intent.type?.startsWith("image/") != true) return emptyList()
    val streams = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> return emptyList()
    }
    val clip = intent.clipData
    val uris = streams.ifEmpty {
        if (clip == null) emptyList() else List(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull()
    }
    return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct()
}

/** A bounded session preview, not the resolution-aware pipeline planned in issue #6. */
fun loadImage(resolver: ContentResolver, uri: Uri): Bitmap {
    require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Unsupported image source" }
    val declaredType = resolver.getType(uri)
    require(declaredType == null || declaredType in supportedMimeTypes) { "Unsupported image type" }
    return loadImage { resolver.openInputStream(uri) ?: error("Unreadable image") }
}

fun loadImage(file: java.io.File): Bitmap = loadImage { file.inputStream() }

private fun loadImage(open: () -> java.io.InputStream): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val metadataStream = open()
    metadataStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType in supportedMimeTypes) {
        "Unsupported or unreadable image"
    }
    val containerStream = open()
    containerStream.use { requireStillImage(it, bounds.outMimeType) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("Unreadable image")
    try {
        val exif = open().use { ExifInterface(it) }
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    } catch (failure: Exception) {
        bitmap.recycle()
        throw failure
    }
}
