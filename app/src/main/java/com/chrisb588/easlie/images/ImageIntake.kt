package com.chrisb588.easlie.images

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

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
