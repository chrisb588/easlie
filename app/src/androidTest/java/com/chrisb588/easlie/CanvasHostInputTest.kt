package com.chrisb588.easlie

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.CanvasPoint
import com.chrisb588.easlie.canvas.CanvasSize
import com.chrisb588.easlie.canvas.CanvasViewport
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Inject through Android's real Activity and overlay windows, using only a temporary board. */
class CanvasHostInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(25)
        }
        error(message)
    }

    @Test fun activityAndFloatingWindowRouteCanvasInputWithoutMovingTheirHosts() {
        assertTrue("Overlay permission is required for host-input verification", Settings.canDrawOverlays(context))
        val previousServiceInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = Uri.parse("content://com.chrisb588.easlie.test.images/clipping.png")
        context.contentResolver.call(fixture, "create-clipping-fixture", null, null)
        val board = (context.applicationContext as EaslieApplication).board
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            action = FloatingBoardService.ACTION_RETURN_TO_APP
        })
        var temporaryId: String? = null
        var originalId: String? = null
        var originalBoards = emptyList<StoredBoard>()
        var originalItems = emptyList<com.chrisb588.easlie.canvas.BoardItem>()
        var originalFull = CanvasViewport()
        var originalFloating = CanvasViewport()
        try {
            await("Original board did not settle") { board.collectionReady && !board.importing && !FloatingBoardService.isServiceRunning }
            main {
                originalId = board.activeBoardId
                originalBoards = board.boards.toList()
                originalItems = board.items.toList()
                originalFull = board.viewportFor(false)
                originalFloating = board.viewportFor(true)
                board.createBoard("Gesture verification ${UUID.randomUUID()}")
            }
            await("Temporary board was not created") { board.collectionReady && board.activeBoardId != originalId }
            temporaryId = board.activeBoardId
            main { board.enqueueImport(context.contentResolver, listOf(fixture, fixture)) }
            await("Temporary images were not imported") { board.items.size == 2 && !board.importing }
            verifyCanvas(board, floating = false)
            scenario.onActivity { it.startForegroundService(FloatingBoardService.startIntent(it)) }
            await("Floating window did not attach") { FloatingBoardService.isBoardAttached }
            verifyCanvas(board, floating = true)
            // A host move changes window position without changing canvas or image transforms.
            val canvasBeforeMove = canvasBounds(true)
            val viewportBeforeMove = board.viewportFor(true)
            val itemsBeforeMove = board.items.toList()
            val header = boundsWithDescription(context.getString(R.string.floating_board_move_label))
            drag(CanvasPoint(header.exactCenterX(), header.exactCenterY()), CanvasPoint(55f, 35f))
            await("Floating host did not move") { canvasBounds(true).left != canvasBeforeMove.left }
            main {
                assertEquals(viewportBeforeMove, board.viewportFor(true))
                assertEquals(itemsBeforeMove, board.items)
            }
        } finally {
            try {
                main { context.stopService(FloatingBoardService.startIntent(context)) }
                await("Floating host did not stop") { !FloatingBoardService.isServiceRunning && !FloatingBoardService.isBoardAttached }
                await("Temporary import did not settle") { !board.importing && board.collectionReady }
                if (originalId != null) {
                    main { board.openBoard(originalId!!) }
                    await("Original active board was not restored") { board.collectionReady && board.activeBoardId == originalId }
                }
                temporaryId?.let { id ->
                    main { board.deleteBoard(id) }
                    await("Temporary board was not removed") { board.boards.none { it.id == id } }
                }
                main {
                    assertEquals("Original board list must be preserved", originalBoards, board.boards)
                    assertEquals(originalId, board.activeBoardId)
                    assertEquals(originalItems, board.items)
                    assertEquals(originalFull, board.viewportFor(false))
                    assertEquals(originalFloating, board.viewportFor(true))
                }
            } finally {
                scenario.close()
                automation.serviceInfo = previousServiceInfo
            }
        }
    }

    private fun verifyCanvas(board: BoardStore, floating: Boolean) {
        val bounds = awaitCanvasBounds(floating)
        val size = CanvasSize(bounds.width().toFloat(), bounds.height().toFloat())
        await("Host canvas layout did not settle") { board.windowSize == size }
        main {
            board.setViewport(CanvasViewport(), floating)
            board.items.toList().forEach { board.update(it.copy(center = CanvasPoint(0f, 0f), width = 100f, height = 100f)) }
        }
        instrumentation.waitForIdleSync()
        val items = board.items.toList()
        val initial = board.viewportFor(floating)
        val start = CanvasPoint(bounds.exactCenterX(), bounds.exactCenterY())
        drag(start, CanvasPoint(70f, 35f))
        await("Unselected-image drag did not pan in floating=$floating") { board.viewportFor(floating) != initial }
        main {
            assertEquals(items, board.items)
            assertEquals(bounds, canvasBounds(floating))
            board.setViewport(initial, floating)
        }
        instrumentation.waitForIdleSync()
        // Off-center focal point: first finger on an image, second finger on empty space.
        val focal = CanvasPoint(size.width / 2f + 60f, size.height / 2f + 20f)
        val first = focal + CanvasPoint(-60f, 0f)
        val second = focal + CanvasPoint(60f, 0f)
        main {
            assertTrue(items.last().contains(initial.windowToWorld(first, size)))
            assertTrue(items.none { it.contains(initial.windowToWorld(second, size)) })
        }
        pinch(
            first + CanvasPoint(bounds.left.toFloat(), bounds.top.toFloat()),
            second + CanvasPoint(bounds.left.toFloat(), bounds.top.toFloat()),
        )
        await("Pinch did not zoom in floating=$floating") { board.viewportFor(floating).zoom > initial.zoom }
        main {
            val next = board.viewportFor(floating)
            val expectedFocal = initial.windowToWorld(focal, size)
            val actualFocal = next.windowToWorld(focal, size)
            assertEquals(expectedFocal.x, actualFocal.x, 1f)
            assertEquals(expectedFocal.y, actualFocal.y, 1f)
            assertEquals(items, board.items)
            assertEquals(bounds, canvasBounds(floating))
            board.setViewport(initial, floating)
        }
        instrumentation.waitForIdleSync()
        // Neither the drag nor pinch selected an image: a fresh drag still pans.
        drag(start, CanvasPoint(-55f, 25f))
        await("Release transferred ownership to an image") { board.viewportFor(floating) != initial }
        main { assertEquals(items, board.items) }
    }

    private fun canvasBounds(floating: Boolean): Rect {
        val roots = automation.windows.mapNotNull { it.root }.filter { it.packageName == context.packageName }
        for (root in roots) {
            val overlay = root.findAccessibilityNodeInfosByText("Floating reference board").isNotEmpty() ||
                find(root) { it.contentDescription == context.getString(R.string.floating_board_overlay_label) } != null
            if (overlay != floating) continue
            find(root) { it.contentDescription?.startsWith("Reference image board.") == true }?.let {
                return Rect().also(it::getBoundsInScreen)
            }
        }
        error("Canvas accessibility bounds unavailable for floating=$floating")
    }

    private fun awaitCanvasBounds(floating: Boolean): Rect {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            // Window attachment precedes the Compose accessibility tree becoming available.
            runCatching { canvasBounds(floating) }.getOrNull()?.takeUnless { it.isEmpty }?.let { return it }
            Thread.sleep(25)
        }
        error("Canvas accessibility tree did not appear for floating=$floating")
    }

    private fun boundsWithDescription(description: String): Rect {
        automation.windows.forEach { window ->
            find(window.root) { it.contentDescription == description }?.let { return Rect().also(it::getBoundsInScreen) }
        }
        error("Missing host control: $description")
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun drag(start: CanvasPoint, delta: CanvasPoint) {
        val downTime = SystemClock.uptimeMillis()
        send(downTime, MotionEvent.ACTION_DOWN, listOf(start))
        for (step in 1..8) {
            Thread.sleep(16)
            send(downTime, MotionEvent.ACTION_MOVE, listOf(start + delta * (step / 8f)))
        }
        send(downTime, MotionEvent.ACTION_UP, listOf(start + delta))
        instrumentation.waitForIdleSync()
    }

    private fun pinch(first: CanvasPoint, second: CanvasPoint) {
        val downTime = SystemClock.uptimeMillis()
        send(downTime, MotionEvent.ACTION_DOWN, listOf(first))
        send(downTime, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(first, second))
        for (step in 1..8) {
            Thread.sleep(16)
            val delta = CanvasPoint(30f * step / 8f, 0f)
            send(downTime, MotionEvent.ACTION_MOVE, listOf(first - delta, second + delta))
        }
        val end = listOf(first - CanvasPoint(30f, 0f), second + CanvasPoint(30f, 0f))
        send(downTime, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), end)
        send(downTime, MotionEvent.ACTION_UP, listOf(end.first()))
        instrumentation.waitForIdleSync()
    }

    private fun send(downTime: Long, action: Int, points: List<CanvasPoint>) {
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
            x = points[index].x; y = points[index].y; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { check(automation.injectInputEvent(event, true)) { "Android rejected input event" } }
        finally { event.recycle() }
    }
}
