package com.chrisb588.easlie

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.chrisb588.easlie.ui.theme.Appearance
import com.chrisb588.easlie.ui.theme.AppearanceStore
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Verify rendered pixels through the real hosts, preserving the user's boards and settings. */
class AppearanceHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun realHostsFollowAppearanceAndSystemChangesAcrossBoardSwitches() {
        assertTrue("Dynamic palette verification requires Android 12+", Build.VERSION.SDK_INT >= 31)
        assertTrue("Grant overlay permission before host verification", Settings.canDrawOverlays(context))
        val app = context.applicationContext as EaslieApplication
        val board = app.board
        val originalAppearance = app.appearance.appearance
        val originalNight = shell("cmd uimode night").trim().substringAfter("Night mode: ")
        check(originalNight in setOf("yes", "no", "auto")) {
            "Use an explicit or automatic system appearance before running this test"
        }
        val originalServiceInfo = automation.serviceInfo
        val originalAccessibilityFlags = originalServiceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val temporaryIds = mutableListOf<String>()
        var originalId: String? = null
        var originalBoards = emptyList<StoredBoard>()
        main { context.stopService(FloatingBoardService.startIntent(context)) }
        await("Previous overlay did not stop") { !FloatingBoardService.isServiceRunning }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            await("Original board did not settle") {
                board.collectionReady && !board.importing && !FloatingBoardService.isServiceRunning
            }
            main { originalId = board.activeBoardId; originalBoards = board.boards.toList() }
            repeat(2) {
                val previous = board.activeBoardId
                main { board.createBoard("Appearance verification ${UUID.randomUUID()}") }
                await("Temporary board did not open") { board.collectionReady && board.activeBoardId != previous }
                temporaryIds += board.activeBoardId!!
            }
            for (floating in listOf(false, true)) {
                if (floating) {
                    // Start directly so the Activity remains alive while its real overlay is attached.
                    scenario.onActivity { it.startForegroundService(FloatingBoardService.startIntent(it)) }
                    await("Overlay did not attach") { FloatingBoardService.isBoardAttached }
                }
                setNight(false)
                main { app.appearance.select(Appearance.LIGHT) }
                assertPalette(floating, dark = false)
                main { app.appearance.select(Appearance.DARK) }
                assertPalette(floating, dark = true)
                setNight(true)
                main { app.appearance.select(Appearance.LIGHT) }
                assertPalette(floating, dark = false)
                main { app.appearance.select(Appearance.SYSTEM) }
                assertPalette(floating, dark = true)
                setNight(false)
                assertPalette(floating, dark = false)
                main { app.appearance.select(Appearance.DARK) }
                assertPalette(floating, dark = true)
                for (id in temporaryIds) {
                    main { board.openBoard(id) }
                    await("Board switch did not settle") { board.collectionReady && board.activeBoardId == id }
                    main { assertEquals(Appearance.DARK, app.appearance.appearance) }
                    assertPalette(floating, dark = true)
                }
                // Read persisted app preferences through a fresh store, after all host and board changes.
                val preferences = context.getSharedPreferences(AppearanceStore.PREFERENCES, Context.MODE_PRIVATE)
                assertTrue(preferences.edit().commit())
                assertEquals(Appearance.DARK, AppearanceStore(preferences).appearance)
            }
        } finally {
            try {
                main { context.stopService(FloatingBoardService.startIntent(context)) }
                await("Overlay did not stop") { !FloatingBoardService.isServiceRunning && !FloatingBoardService.isBoardAttached }
                await("Board did not settle for cleanup") { board.collectionReady && !board.importing }
                originalId?.let { id ->
                    main { board.openBoard(id) }
                    await("Original board was not restored") { board.collectionReady && board.activeBoardId == id }
                }
                for (id in temporaryIds.reversed()) {
                    main { board.deleteBoard(id) }
                    await("Temporary board was not deleted") { board.collectionReady && board.boards.none { it.id == id } }
                }
                main {
                    assertEquals(originalId, board.activeBoardId)
                    assertEquals(originalBoards, board.boards)
                }
            } finally {
                main { app.appearance.select(originalAppearance) }
                shell("cmd uimode night $originalNight")
                scenario.close()
                automation.serviceInfo = originalServiceInfo.apply { flags = originalAccessibilityFlags }
            }
        }
    }

    private fun setNight(dark: Boolean) { shell("cmd uimode night ${if (dark) "yes" else "no"}") }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }

    private fun assertPalette(floating: Boolean, dark: Boolean) {
        val palette = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val deadline = SystemClock.uptimeMillis() + 10000
        var observed: Int? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.windows.mapNotNull { it.root }.firstOrNull { root ->
                root.packageName == context.packageName &&
                    (find(root) { it.contentDescription == context.getString(R.string.floating_board_overlay_label) } != null) == floating &&
                    find(root) { it.contentDescription?.startsWith("Reference image board.") == true } != null
            }
            val canvas = root?.let { find(it) { it.contentDescription?.startsWith("Reference image board.") == true } }
            if (canvas != null) {
                val bounds = Rect().also(canvas::getBoundsInScreen)
                val screenshot = automation.takeScreenshot()
                if (screenshot != null) {
                    try {
                        observed = screenshot.getPixel(bounds.centerX(), bounds.centerY())
                        val header = if (floating) find(root) {
                            it.contentDescription == context.getString(R.string.floating_board_move_label)
                        } else null
                        val headerBounds = header?.let { Rect().also(it::getBoundsInScreen) }
                        val chromeMatches = !floating || (headerBounds != null &&
                            screenshot.getPixel(headerBounds.centerX(),
                                headerBounds.top + (8 * context.resources.displayMetrics.density).toInt()) == palette.onSurfaceVariant.toArgb())
                        if (observed == palette.background.toArgb() && chromeMatches) return
                    } finally { screenshot.recycle() }
                }
            }
            Thread.sleep(100)
        }
        fail("Real host palette did not update: floating=$floating dark=$dark expected=${palette.background.toArgb()} actual=$observed")
    }

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

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            find(child, predicate)?.let { return it }
        }
        return null
    }
}
