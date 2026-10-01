package com.chrisb588.easlie

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercise the document/multiple-task flags observed in browser and file-manager shares. */
class ShareTaskTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val tasks get() = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks
    private lateinit var first: Uri
    private lateinit var second: Uri

    @Before
    fun prepareSourcesAndRemoveOldTestTasks() {
        // Start from one launcher task, including when the old build left duplicate Recents entries.
        instrumentation.runOnMainSync { tasks.forEach { it.finishAndRemoveTask() } }
        context.contentResolver.call(Uri.parse("content://com.chrisb588.easlie.test.images"),
            "create-task-fixtures", null, null)
        first = Uri.parse("content://com.chrisb588.easlie.test.images/task-first.png")
        second = Uri.parse("content://com.chrisb588.easlie.test.images/task-second.png")
    }

    @Test
    fun singleAndMultipleSharesReuseTheLauncherActivityAndKeepExistingItems() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: MainActivity
            lateinit var board: BoardStore
            var originalTaskId = -1
            scenario.onActivity {
                original = it
                originalTaskId = it.taskId
                board = (it.application as EaslieApplication).board
                board.items.toList().forEach { item -> board.delete(item.id) }
            }

            sendShare(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, first))
            waitForItems(board, 1)
            val existingItem = board.items.single()
            scenario.onActivity { assertSame(original, it) }
            assertSingleTask(originalTaskId)

            // A sender may request a fresh document task on every share. Easlie must still reuse its board.
            sendShare(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second)))
            waitForItems(board, 3)
            scenario.onActivity { assertSame(original, it) }
            assertSingleTask(originalTaskId)
            assertEquals(existingItem, board.items.first())
            assertEquals(3, board.items.map { it.id }.distinct().size)

            // Returning through the launcher must also reuse that same activity without replaying a share.
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertSame(original, it) }
            assertSingleTask(originalTaskId)
            assertEquals(3, board.items.size)
        }
    }

    private fun sendShare(intent: Intent) {
        val share = intent.setClass(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_FORWARD_RESULT or
                Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        context.startActivity(Intent().setClassName(instrumentation.context.packageName,
            "com.chrisb588.easlie.ShareSenderActivity").putExtra("share", share)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    private fun assertSingleTask(taskId: Int) {
        val currentTasks = tasks
        assertEquals("External shares must reuse one easlie task", 1, currentTasks.size)
        assertEquals(taskId, requireNotNull(currentTasks.single().taskInfo).taskId)
    }

    private fun waitForItems(board: BoardStore, count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = board.items.size == count && !board.importing }
            if (ready) return
            Thread.sleep(25)
        }
        instrumentation.runOnMainSync {
            assertEquals(count, board.items.size)
            assertTrue("Import did not finish", !board.importing)
        }
    }
}
