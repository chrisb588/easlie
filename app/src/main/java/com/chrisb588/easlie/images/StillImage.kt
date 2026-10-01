package com.chrisb588.easlie.images

import java.io.DataInputStream
import java.io.InputStream

/** BitmapFactory can return a first frame for animated containers; reject those before decoding. */
internal fun requireStillImage(stream: InputStream, mimeType: String) {
    val input = DataInputStream(stream)
    when (mimeType) {
        "image/png" -> {
            input.skipExactly(8) // PNG signature; BitmapFactory already recognized it.
            while (true) {
                val length = input.readInt()
                require(length >= 0) { "Invalid PNG chunk" }
                val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                require(type != "acTL") { "Animated images are not supported" }
                if (type == "IDAT" || type == "IEND") return
                input.skipExactly(length.toLong() + 4) // Chunk contents and CRC.
            }
        }
        "image/webp" -> {
            input.skipExactly(12) // RIFF header and WEBP signature.
            val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            if (type == "VP8X") {
                input.skipExactly(4) // Extended chunk length.
                require(input.readUnsignedByte() and 0x02 == 0) { "Animated images are not supported" }
            }
        }
    }
}

private fun DataInputStream.skipExactly(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped == 0L) {
            readUnsignedByte() // Throws on truncated input instead of looping indefinitely.
            remaining--
        } else {
            remaining -= skipped
        }
    }
}
