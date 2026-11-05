package com.chrisb588.easlie

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.ui.theme.EaslieTheme
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PartialImportUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun partialImportShowsFailuresAndRetriesOnlyTheTemporarySource() {
        val root = File(rule.activity.cacheDir, "partial-ui-${UUID.randomUUID()}")
        val resolver = rule.activity.contentResolver
        val good = Uri.parse("content://com.chrisb588.easlie.test.images/partial-ui-good.png")
        val temporary = Uri.parse("content://com.chrisb588.easlie.test.images/partial-ui-temporary.png")
        val invalid = Uri.parse("content://com.chrisb588.easlie.test.images/partial-ui-invalid.png")
        fun writeImage(uri: Uri) {
            val bitmap = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
            try { resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        try {
            writeImage(good)
            writeImage(temporary)
            resolver.call(temporary, "fail-next-read", temporary.lastPathSegment, null)
            resolver.openOutputStream(invalid)!!.use { it.write("invalid pixels".toByteArray()) }
            lateinit var store: BoardStore
            rule.runOnUiThread { store = BoardStore(File(root, "board"), collectionMigration = true) }
            rule.waitUntil(5000) { store.collectionReady }
            rule.runOnUiThread { store.createBoard("Partial import") }
            rule.waitUntil(5000) { store.activeBoardId != null }
            rule.setContent { EaslieTheme { FloatingBoardScreen(store, {}, false, null, {}, {}) } }
            rule.runOnUiThread {
                store.resizeWindow(CanvasSize(600f, 400f))
                store.enqueueImport(resolver, listOf(good, temporary, invalid))
            }
            rule.waitUntil(5000) { !store.importing && store.canRetryImport }
            val committed = rule.runOnIdle { store.items.single() }
            val destination = rule.runOnIdle { store.activeBoardId!! }
            rule.onNodeWithText("partial-ui-temporary.png", substring = true).assertIsDisplayed()
            rule.onNodeWithText("partial-ui-invalid.png", substring = true).assertIsDisplayed()
            rule.onNodeWithText("Retry failed images").assertIsDisplayed()
            assertEquals(listOf(committed), BoardStorage(File(root, "boards/$destination")).load().snapshot.items)

            // Valid bytes here would create a third item if invalid inputs were retried.
            writeImage(invalid)
            rule.onNodeWithText("Retry failed images").performClick()
            rule.waitUntil(5000) { !store.importing && !store.canRetryImport && store.items.size == 2 }
            rule.onNodeWithText("Retry failed images").assertDoesNotExist()
            rule.runOnIdle { assertEquals(committed, store.items.first()) }
            assertEquals(2, BoardStorage(File(root, "boards/$destination")).load().snapshot.items.size)
            assertEquals(2, File(root, "boards/$destination/assets").listFiles()!!.size)
        } finally {
            listOf(good, temporary, invalid).forEach { resolver.delete(it, null, null) }
            root.deleteRecursively()
        }
    }
}
