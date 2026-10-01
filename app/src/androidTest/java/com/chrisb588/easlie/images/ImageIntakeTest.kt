package com.chrisb588.easlie.images

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class ImageIntakeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private fun uri(name: String) = Uri.parse("content://com.chrisb588.easlie.test.images/$name")

    @Test
    fun singleMultipleAndClipPayloadsAreExtractedInOrder() {
        val first = uri("first.png")
        val second = uri("second.png")
        val single = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, first)
        assertEquals(listOf(first), sharedImageUris(single))
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
        assertEquals(listOf(first, second), sharedImageUris(multiple))
        val clip = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").apply {
            clipData = ClipData.newRawUri("images", first).apply { addItem(ClipData.Item(second)) }
        }
        assertEquals(listOf(first, second), sharedImageUris(clip))
        assertTrue(sharedImageUris(Intent(Intent.ACTION_VIEW).setType("image/png").putExtra(Intent.EXTRA_STREAM, first)).isEmpty())
        assertTrue(sharedImageUris(single.setType("text/plain")).isEmpty())
    }

    @Test
    fun sourcePixelsAreSubsampledAndExifOrientationIsApplied() = runBlocking {
        val file = File(context.filesDir, "rotated.jpg")
        val original = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        file.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        original.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val source = copyImageSource(context.contentResolver, uri(file.name), context.cacheDir)
        val loaded = source.decode(4)
        assertTrue(loaded.height > loaded.width)
        assertTrue(maxOf(loaded.width, loaded.height) <= 1024)
        loaded.recycle()
        source.file.delete()
        file.delete()
    }

    @Test
    fun unsupportedMissingAndCorruptInputsAreRejected() = runBlocking {
        File(context.filesDir, "unsupported.txt").writeText("not an image")
        File(context.filesDir, "corrupt.png").writeText("not a png")
        for (name in listOf("unsupported.txt", "corrupt.png", "missing.png")) {
            try {
                copyImageSource(context.contentResolver, uri(name), context.cacheDir)
                fail("Expected rejection of $name")
            } catch (_: Exception) {
                // A rejected source must never yield a bitmap for the store to append.
            }
        }
    }
}
