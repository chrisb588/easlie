package com.chrisb588.easlie

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.canvas.*
import com.chrisb588.easlie.images.ImageRenderer
import com.chrisb588.easlie.images.readImageSource
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sin

/** Opt-in physical-device experiment. Never part of the ordinary connected-test suite. */
class RealBoardProfileTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            onMain { done = condition() }
            if (done) return
            Thread.sleep(20)
        }
        error("Profiling workload did not settle")
    }

    @Test fun profileSuppliedBoard() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e profileRealBoard true to use the user's real board", args.getString("profileRealBoard") == "true")
        val seconds = (args.getString("profileSeconds") ?: "120").toInt().also { require(it in 5..600) }
        val repetitions = (args.getString("profileRepetitions") ?: "3").toInt().also { require(it in 1..10) }
        val profileFloating = args.getString("profileFloating") != "false"
        val floatingOnly = args.getString("profileFloatingOnly") == "true"
        require(!floatingOnly || profileFloating)
        check(!profileFloating || Settings.canDrawOverlays(context)) { "Grant floating-board permission in the app before profiling" }
        val storage = BoardStorage(File(context.filesDir, "board"))
        val original = storage.load().snapshot
        require(original.items.size in 20..30) { "Supply 20 to 30 real images on the board first" }
        val sources = original.items.associate { it.id to readImageSource(storage.asset(it.assetId)) }
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        Log.i(TAG, "device=${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE} memory_class_mb=$memoryClass seconds=$seconds repetitions=$repetitions")
        original.items.forEachIndexed { index, item ->
            val source = sources.getValue(item.id)
            Log.i(TAG, "image=${index + 1} width=${source.width} height=${source.height} bytes=${source.file.length()}")
        }
        lateinit var board: BoardStore
        onMain { board = (context.applicationContext as EaslieApplication).board }
        var scenario: ActivityScenario<MainActivity>? = null
        val fixtureUris = mutableListOf<Uri>()
        fun launch() {
            scenario?.close()
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).apply {
                action = FloatingBoardService.ACTION_RETURN_TO_APP
            })
            await { !FloatingBoardService.isServiceRunning && board.windowSize.width > 0 && board.items.size >= original.items.size }
        }
        fun stage(name: String, repeat: Int, snapshot: () -> Unit = { board.logImageProfile() }, action: () -> Unit) {
            Log.i(TAG, "stage=$name repeat=$repeat event=start time_ns=${System.nanoTime()}")
            onMain {
                Log.i(TAG, "host_width_px=${board.windowSize.width} host_height_px=${board.windowSize.height}")
                snapshot()
            }
            action()
            Thread.sleep(1000) // Include outstanding background decodes in the end snapshot.
            onMain(snapshot)
            Log.i(TAG, "stage=$name repeat=$repeat event=end time_ns=${System.nanoTime()}")
        }
        fun animate(action: (Float) -> Unit) {
            val start = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - start < seconds * 1000L) {
                val elapsed = (SystemClock.uptimeMillis() - start) / 1000f
                onMain { action(elapsed) }
                Thread.sleep(16)
            }
        }
        val left = original.items.minOf { it.center.x - it.width / 2 }
        val right = original.items.maxOf { it.center.x + it.width / 2 }
        val centerY = original.items.map { it.center.y }.average().toFloat()
        fun viewport(t: Float) = CanvasViewport(
            CanvasPoint(left + (right - left) * (sin(t * 0.7f) + 1f) / 2f, centerY), 0.8f)
        try {
            launch()
            await { board.items.size == original.items.size && board.images.isNotEmpty() }
            for (repeat in 1..repetitions) {
                onMain { board.releaseImages(); board.viewport = original.fullScreen }
                Thread.sleep(1000)
                if (!floatingOnly) {
                    stage("pan", repeat) { animate { board.viewport = viewport(it) } }
                    stage("zoom", repeat) {
                        animate { t ->
                            val item = original.items[(t / 4).toInt() % original.items.size]
                            board.viewport = CanvasViewport(item.center, 0.3f + (sin(t * 1.6f) + 1f) * 4f)
                        }
                    }
                    stage("import", repeat) {
                        // Replay real source bytes through the normal import path, then remove only new items.
                        val before = board.items.map { it.id }.toSet()
                        val uris = (0..2).map { index ->
                            val uri = Uri.parse("content://com.chrisb588.easlie.test.images/profile-$repeat-$index.jpg")
                            fixtureUris.add(uri)
                            context.contentResolver.openOutputStream(uri)!!.use { output ->
                                sources.getValue(original.items[(repeat * 3 + index) % original.items.size].id).file.inputStream().use { it.copyTo(output) }
                            }
                            uri
                        }
                        onMain { board.enqueueImport(context.contentResolver, uris) }
                        await { !board.importing && board.items.size == before.size + uris.size }
                        val newIds = mutableListOf<String>()
                        onMain { newIds.addAll(board.items.filter { it.id !in before }.map { it.id }); newIds.forEach(board::delete) }
                        await { board.items.size == before.size }
                    }
                }
                if (profileFloating) stage("floating", repeat) {
                    scenario!!.onActivity { it.startForegroundService(FloatingBoardService.startIntent(it)) }
                    await { FloatingBoardService.isBoardAttached }
                    // Match the product's single interactive host behavior.
                    scenario!!.onActivity { it.finish() }
                    val start = SystemClock.uptimeMillis()
                    var grow = true
                    val sizes = mutableSetOf<CanvasSize>()
                    val display = if (Build.VERSION.SDK_INT >= 30) {
                        context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                    } else Rect(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
                    while (SystemClock.uptimeMillis() - start < seconds * 1000L) {
                        val bounds = resizeHandleBounds()
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        val endX = if (grow) display.right - 48f else display.left + 48f
                        val endY = if (grow) display.bottom - 96f else display.top + 48f
                        drag(x, y, endX - x, endY - y)
                        grow = !grow
                        val elapsed = (SystemClock.uptimeMillis() - start) / 1000f
                        onMain {
                            sizes.add(board.windowSize)
                            board.setViewport(viewport(elapsed).copy(zoom = 0.3f + (sin(elapsed) + 1f) * 2), true)
                        }
                    }
                    assertTrue("Touch drags must actually resize the floating canvas", sizes.size >= 2)
                    sizes.forEach { Log.i(TAG, "floating_width_px=${it.width} floating_height_px=${it.height}") }
                    onMain { context.stopService(FloatingBoardService.startIntent(context)) }
                    await { !FloatingBoardService.isServiceRunning }
                    launch()
                }
                else Log.i(TAG, "floating_skipped=true repeat=$repeat")
                assertTrue("Original images must survive every cycle", board.items.map { it.id }.containsAll(original.items.map { it.id }))
            }
            // Compare bounded-cache budgets using the same sources and deterministic renderer requests.
            // This experiment does not draw frames and is reported separately from the real hosts above.
            scenario?.close(); scenario = null
            onMain { board.releaseImages() }
            if (!floatingOnly) for (divisor in listOf(16, 8, 4)) {
                for (repeat in 1..repetitions) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    val renderer = ImageRenderer(scope, memoryClass.toLong() * 1024 * 1024 / divisor, true)
                    try {
                        stage("cache-$divisor", repeat, snapshot = { renderer.logProfile() }) {
                            animate { t ->
                                val item = original.items[(t / 3).toInt() % original.items.size]
                                val view = if (t.toInt() % 6 < 3) viewport(t) else CanvasViewport(item.center, 0.3f + (sin(t * 1.6f) + 1f) * 4f)
                                renderer.refresh(original.items, sources, view, CanvasSize(2560f, 1444f), context.resources.displayMetrics.density)
                            }
                            Thread.sleep(1000)
                            onMain { renderer.logProfile() }
                        }
                    } finally {
                        onMain { renderer.clear(); scope.cancel() }
                        scope.coroutineContext[Job]!!.join()
                    }
                }
            }
        } finally {
            try {
                onMain { context.stopService(FloatingBoardService.startIntent(context)) }
                await { !FloatingBoardService.isServiceRunning }
            } finally {
                try {
                    onMain {
                        board.items.filter { item -> original.items.none { it.id == item.id } }.forEach { board.delete(it.id) }
                        original.items.forEach(board::update)
                        board.setViewport(original.fullScreen, false)
                        board.setViewport(original.floating, true)
                        board.save()
                    }
                    await { storage.load().snapshot == original }
                    Log.i(TAG, "board_restored=true event=complete")
                } finally {
                    scenario?.close()
                    fixtureUris.forEach { context.contentResolver.delete(it, null, null) }
                }
            }
        }
    }

    private fun resizeHandleBounds(): Rect {
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if (node == null) return null
            if (node.contentDescription?.toString() == context.getString(R.string.floating_board_resize_label)) {
                return Rect().also(node::getBoundsInScreen)
            }
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            automation.windows.forEach { find(it.root)?.let { bounds -> if (!bounds.isEmpty) return bounds } }
            Thread.sleep(50)
        }
        error("Floating resize handle is unavailable")
    }

    private fun drag(x: Float, y: Float, dx: Float, dy: Float) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * fraction, y + dy * fraction, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..12) { Thread.sleep(16); send(MotionEvent.ACTION_MOVE, step / 12f) }
        send(MotionEvent.ACTION_UP, 1f)
    }

    companion object { private const val TAG = "EaslieBoardProfile" }
}
