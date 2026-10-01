package com.chrisb588.easlie

import com.chrisb588.easlie.images.requireStillImage
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class StillImageTest {
    private fun png(vararg chunks: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(ByteArray(8))
            for (chunk in chunks) {
                out.writeInt(0)
                out.writeBytes(chunk)
                out.writeInt(0)
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun staticPngAndWebpHeadersAreAccepted() {
        requireStillImage(ByteArrayInputStream(png("IHDR", "IDAT")), "image/png")
        requireStillImage(ByteArrayInputStream(ByteArray(12) + "VP8 ".toByteArray()), "image/webp")
        requireStillImage(ByteArrayInputStream(ByteArray(12) + "VP8X".toByteArray() + ByteArray(4) + byteArrayOf(0)), "image/webp")
    }

    @Test(expected = IllegalArgumentException::class)
    fun animatedPngIsRejectedBeforeItsFirstFrame() {
        requireStillImage(ByteArrayInputStream(png("IHDR", "acTL", "IDAT")), "image/png")
    }

    @Test(expected = IllegalArgumentException::class)
    fun animatedWebpIsRejectedBeforeItsFirstFrame() {
        requireStillImage(ByteArrayInputStream(ByteArray(12) + "VP8X".toByteArray() + ByteArray(4) + byteArrayOf(2)), "image/webp")
    }

    @Test(expected = java.io.EOFException::class)
    fun truncatedMetadataIsRejected() {
        requireStillImage(ByteArrayInputStream(ByteArray(10)), "image/png")
    }
}
