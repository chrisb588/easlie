package com.chrisb588.easlie

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import java.io.File
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasViewport
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real overlay/action windows, using a disposable empty board and restoring the original active board. */
class FloatingBoardLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        error("$message; window packages/focus: ${automation.windows.map { it.root?.packageName.toString() to it.isFocused }}")
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
        repeat(node.childCount) { find(node.getChild(it), label)?.let { found -> return found } }
        return null
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val roots = automation.windows.mapNotNull { it.root }
        if (FloatingBoardService.isBoardAttached && label in setOf(
                "Open board", "Return to full-screen mode", "Close floating board", "Add images")) {
            // Hidden overlay controls must not match the fullscreen host beneath this fixture.
            return roots.firstOrNull { find(it, "Floating reference board") != null }?.let { find(it, label) }
        }
        return roots.firstNotNullOfOrNull { find(it, label) }
    }
    private fun click(label: String) {
        if (node(label) == null) node("Move floating board")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await("Control unavailable: $label") { node(label) != null }
        var control = node(label)!!
        while (!control.isClickable && control.parent != null) control = control.parent
        assertTrue("Click failed: $label", control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun cancelPicker() {
        val close = listOf("Close", "Cancel", "Navigate up").firstOrNull { node(it) != null }
        if (close != null) click(close) else {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("input keyevent KEYCODE_BACK")
            ).use { it.readBytes() }
        }
    }
    private fun bounds(label: String): Rect = Rect().also { node(label)!!.getBoundsInScreen(it) }
    private fun touch(action: Int, bounds: Rect, start: Long) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        automation.injectInputEvent(event, true)
        event.recycle()
    }
    private fun drag(bounds: Rect, dx: Float, dy: Float) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX() + dx * fraction, bounds.exactCenterY() + dy * fraction, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        repeat(10) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, (it + 1) / 10f) }
        send(MotionEvent.ACTION_UP, 1f)
    }
    @Test fun actionsCancelAndRepeatedCloseAndReturnLeaveOneOrNoOverlay() {
        assertTrue("Overlay permission required", Settings.canDrawOverlays(context))
        val previousInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val board = (context.applicationContext as EaslieApplication).board
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            action = FloatingBoardService.ACTION_RETURN_TO_APP
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        var previousId: String? = null
        var fixtureId: String? = null
        var previousBoards = emptyList<StoredBoard>()
        val full = CanvasViewport(CanvasPoint(100f, -30f), 2f)
        var capturedOriginal = false
        var primaryFailure: Throwable? = null
        try {
            await("Board not ready") { board.collectionReady && !board.importing && !FloatingBoardService.isServiceRunning }
            main {
                previousId = board.activeBoardId
                previousBoards = board.boards.toList()
                capturedOriginal = true
                board.createBoard("Floating lifecycle ${UUID.randomUUID()}")
            }
            await("Fixture not created") { board.collectionReady && board.activeBoardId != previousId }
            fixtureId = board.activeBoardId
            main { board.viewport = full }
            repeat(2) {
                main { context.startForegroundService(FloatingBoardService.startIntent(context)) }
                await("Overlay did not attach") { FloatingBoardService.isBoardAttached && node("Close floating board") != null }
                main { context.startForegroundService(FloatingBoardService.startIntent(context)) }
                assertEquals(1, automation.windows.count { find(it.root, "Close floating board") != null })
                val notifications = context.getSystemService(NotificationManager::class.java)
                await("Foreground notification did not appear (grant POST_NOTIFICATIONS for this test)") { notifications.activeNotifications.size == 1 }
                val viewport = board.viewportFor(true)
                val before = bounds("Floating reference board")
                val density = context.resources.displayMetrics.density
                drag(bounds("Resize floating board"), -200 * density, -100 * density)
                await("Right resize did not reach minimum") {
                    val size = bounds("Floating reference board")
                    size.width() == (280 * density).toInt() && size.height() == (248 * density).toInt()
                }
                val right = bounds("Floating reference board").right
                drag(bounds("Resize floating board from bottom left"), -32 * density, 16 * density)
                await("Left resize did not preserve right edge") {
                    val size = bounds("Floating reference board")
                    size.width() > (280 * density).toInt() && kotlin.math.abs(size.right - right) <= 1
                }
                main { assertEquals(viewport, board.viewportFor(true)); assertEquals(full, board.viewport) }
                val afterResize = bounds("Floating reference board")
                drag(bounds("Move floating board"), 24 * density, 24 * density)
                await("Drag bar did not move host") { bounds("Floating reference board").left != afterResize.left }
                main { assertEquals(viewport, board.viewportFor(true)); assertEquals(full, board.viewport) }
                assertTrue(before.width() > 0)
                val move = node("Move floating board")!!
                val bounds = Rect().also(move::getBoundsInScreen)
                assertTrue(bounds.height() >= (48 * context.resources.displayMetrics.density).toInt())
                assertEquals((200 * density).toInt(), bounds.width())
                assertEquals((64 * density).toInt(), bounds("Resize floating board").height())
                if (it == 0) {
                    SystemClock.sleep(4800)
                    // Only the disposable empty fixture is captured for anchor visual QA.
                    main { assertTrue(board.items.isEmpty()); assertEquals(fixtureId, board.activeBoardId) }
                    val overlay = bounds("Floating reference board")
                    automation.takeScreenshot()?.let { screen ->
                        val crop = Bitmap.createBitmap(screen, overlay.left, overlay.top, overlay.width(), overlay.height())
                        File(context.cacheDir, "floating-anchors-review.png").outputStream().use { output ->
                            crop.compress(Bitmap.CompressFormat.PNG, 100, output)
                        }
                        crop.recycle(); screen.recycle()
                    }
                    listOf("Open board", "Return to full-screen mode", "Close floating board", "Add images").forEach { label ->
                        assertNull("Chrome did not hide: $label", node(label))
                    }
                    assertNotNull(node("Move floating board"))
                    assertNotNull(node("Resize floating board"))
                    assertNotNull(node("Resize floating board from bottom left"))
                    val start = SystemClock.uptimeMillis()
                    touch(MotionEvent.ACTION_DOWN, overlay, start)
                    touch(MotionEvent.ACTION_UP, overlay, start)
                    await("Canvas touch did not reveal chrome") { node("Close floating board") != null }
                    val holdStart = SystemClock.uptimeMillis()
                    touch(MotionEvent.ACTION_DOWN, overlay, holdStart)
                    SystemClock.sleep(4300)
                    assertNotNull("Controls hid during a held touch", node("Close floating board"))
                    touch(MotionEvent.ACTION_UP, overlay, holdStart)
                }
                click("Open board")
                if (it == 0) {
                    SystemClock.sleep(4300)
                    assertNotNull("Menu hid during board selection", node("New board"))
                }
                click("New board")
                await("Creation form did not open") { node("Cancel") != null }
                assertNull(node("Close floating board"))
                click("Cancel")
                await("Cancel did not restore overlay") { node("Close floating board") != null }
                assertEquals(fixtureId, board.activeBoardId)
                click("Add images")
                await("Picker did not hide overlay") { node("Close floating board") == null }
                val pickerPackage = ActivityResultContracts.PickMultipleVisualMedia().createIntent(context,
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    .resolveActivity(context.packageManager).packageName
                await("Picker did not become focused (resolved $pickerPackage)") {
                    automation.windows.any { window -> window.isFocused && window.root?.packageName?.toString()?.let {
                        it == pickerPackage || it.contains("photopicker") || it.contains("providers.media") || it.contains("documentsui")
                    } == true }
                }
                automation.waitForIdle(500, 5000)
                cancelPicker()
                await("Picker cancellation did not restore overlay") { node("Close floating board") != null }
                main { assertEquals(full, board.viewport); assertEquals(fixtureId, board.activeBoardId) }
                click(if (it == 0) "Return to full-screen mode" else "Close floating board")
                await("Overlay did not clean up") { !FloatingBoardService.isBoardAttached && !FloatingBoardService.isServiceRunning }
                assertNull(node("Close floating board"))
                await("Foreground notification lingered") { notifications.activeNotifications.isEmpty() }
            }
            // A delayed action completion must not create a service with no window.
            main { context.startService(FloatingBoardService.startIntent(context).setAction(FloatingBoardService.ACTION_SHOW)) }
            SystemClock.sleep(200)
            await("Stale restore leaked a service") { !FloatingBoardService.isServiceRunning && !FloatingBoardService.isBoardAttached }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                main { context.stopService(FloatingBoardService.startIntent(context)) }
                await("Service did not stop") { !FloatingBoardService.isServiceRunning }
                await("Board operation did not settle") { board.collectionReady && !board.importing }
                previousId?.let { id ->
                    main { board.openBoard(id) }
                    await("Original board not restored") { board.collectionReady && board.activeBoardId == id }
                }
                fixtureId?.let { id ->
                    main { board.deleteBoard(id) }
                    await("Fixture not deleted") { board.collectionReady && board.boards.none { it.id == id } }
                }
                if (capturedOriginal) main {
                    assertEquals(previousId, board.activeBoardId)
                    assertEquals(previousBoards, board.boards)
                }
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) throw cleanupFailure
                primaryFailure.addSuppressed(cleanupFailure)
            } finally {
                main {
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                        .flatMap { monitor.getActivitiesInStage(it) }.toSet().forEach { it.finish() }
                }
                automation.serviceInfo = previousInfo
            }
        }
    }
}
