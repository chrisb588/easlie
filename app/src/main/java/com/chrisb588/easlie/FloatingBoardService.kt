package com.chrisb588.easlie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class FloatingBoardService : Service() {
    private var windowManager: WindowManager? = null
    private var windowContext: Context? = null
    private var boardView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopAndCleanUp()
                START_NOT_STICKY
            }

            ACTION_RETURN_TO_APP -> {
                stopAndCleanUp()
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
                START_NOT_STICKY
            }

            else -> {
                startFloatingBoard(getStartResultReceiver(intent))
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        removeBoardWindow()
        if (foregroundStarted) {
            stopForeground(true)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    private fun startForegroundIfNeeded() {
        if (foregroundStarted) return

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun startFloatingBoard(resultReceiver: ResultReceiver?) {
        if (!Settings.canDrawOverlays(this)) {
            sendStartFailure(
                resultReceiver,
                getString(R.string.floating_board_permission_denied)
            )
            stopAndCleanUp()
            return
        }

        try {
            startForegroundIfNeeded()
            if (attachBoardIfNeeded()) {
                resultReceiver?.send(RESULT_ATTACHED, Bundle())
            } else {
                sendStartFailure(
                    resultReceiver,
                    getString(R.string.floating_board_attach_failed)
                )
                stopAndCleanUp()
            }
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Unable to start floating board", exception)
            sendStartFailure(
                resultReceiver,
                exception.message ?: exception.javaClass.simpleName
            )
            stopAndCleanUp()
        }
    }

    private fun attachBoardIfNeeded(): Boolean {
        if (boardView != null) return true

        val type = overlayWindowType()
        val contextForWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(DisplayManager::class.java)
            val display = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY)) {
                "The default display is unavailable"
            }
            createDisplayContext(display).createWindowContext(type, null)
        } else {
            this
        }
        val manager = contextForWindow.getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            dp(DEFAULT_WIDTH_DP),
            dp(DEFAULT_HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(DEFAULT_X_DP)
            y = dp(DEFAULT_Y_DP)
        }

        try {
            val view = createBoardView(contextForWindow)
            manager.addView(view, params)
            windowContext = contextForWindow
            windowManager = manager
            layoutParams = params
            boardView = view
            return true
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Unable to attach floating board", exception)
            return false
        }
    }

    private fun sendStartFailure(resultReceiver: ResultReceiver?, message: String) {
        resultReceiver?.send(
            RESULT_FAILED,
            Bundle().apply {
                putString(EXTRA_RESULT_MESSAGE, message)
            }
        )
    }

    private fun getStartResultReceiver(intent: Intent?): ResultReceiver? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                EXTRA_START_RESULT_RECEIVER,
                ResultReceiver::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_START_RESULT_RECEIVER)
        }
    }

    private fun createBoardView(context: Context): View {
        val root = FrameLayout(context).apply {
            contentDescription = getString(R.string.floating_board_overlay_label)
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 36, 40))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val header = TextView(context).apply {
            text = getString(R.string.floating_board_drag_hint)
            contentDescription = getString(R.string.floating_board_move_label)
            setTextColor(Color.WHITE)
            setGravity(Gravity.CENTER_VERTICAL)
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.rgb(74, 76, 84))
        }
        content.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        val controls = LinearLayout(context).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val returnButton = Button(context).apply {
            text = getString(R.string.return_to_easlie)
            contentDescription = getString(R.string.floating_board_return_label)
            setOnClickListener { returnToFullScreen() }
        }
        val closeButton = Button(context).apply {
            text = getString(R.string.stop_floating_board)
            contentDescription = getString(R.string.floating_board_close_label)
            setOnClickListener { stopAndCleanUp() }
        }
        controls.addView(returnButton, buttonLayoutParams())
        controls.addView(closeButton, buttonLayoutParams())
        content.addView(
            controls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        val boardText = TextView(context).apply {
            text = getString(R.string.floating_board_content)
            contentDescription = getString(R.string.floating_board_overlay_label)
            setTextColor(Color.WHITE)
            setGravity(Gravity.CENTER)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(
            boardText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val resizeHandle = TextView(context).apply {
            text = "↘"
            textSize = 22f
            contentDescription = getString(R.string.floating_board_resize_label)
            setTextColor(Color.WHITE)
            setGravity(Gravity.CENTER)
            setBackgroundColor(Color.rgb(74, 76, 84))
        }
        root.addView(
            resizeHandle,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.RIGHT)
        )

        val leftResizeHandle = TextView(context).apply {
            text = "↙"
            textSize = 22f
            contentDescription = getString(R.string.floating_board_resize_left_label)
            setTextColor(Color.WHITE)
            setGravity(Gravity.CENTER)
            setBackgroundColor(Color.rgb(74, 76, 84))
        }
        root.addView(
            leftResizeHandle,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.LEFT)
        )

        addMoveListener(header)
        addResizeListener(resizeHandle)
        addResizeListener(leftResizeHandle, fromLeft = true)
        return root
    }

    private fun addMoveListener(handle: View) {
        var startRawX = 0f
        var startRawY = 0f
        var initialX = 0
        var initialY = 0

        handle.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    synchronizeBoardPosition()
                    initialX = params.x
                    initialY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - startRawX).roundToInt()
                    params.y = initialY + (event.rawY - startRawY).roundToInt()
                    updateBoardLayout()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun addResizeListener(handle: View, fromLeft: Boolean = false) {
        var startRawX = 0f
        var startRawY = 0f
        var initialWidth = 0
        var initialHeight = 0
        var initialX = 0
        var initialY = 0

        handle.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    synchronizeBoardPosition()
                    initialX = params.x
                    initialY = params.y
                    initialWidth = params.width
                    initialHeight = params.height
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val bounds = availableBoardBounds()
                    val deltaX = (event.rawX - startRawX).roundToInt()
                    val deltaY = (event.rawY - startRawY).roundToInt()
                    if (fromLeft) {
                        val resized = FloatingBoardGeometry.resizedFromLeft(
                            initialX, initialWidth, initialHeight, deltaX, deltaY,
                            dp(MIN_WIDTH_DP), dp(MIN_HEIGHT_DP),
                            (bounds.height() - initialY).coerceAtLeast(1)
                        )
                        // Anchor the window itself to the fixed right edge. Updating
                        // a left offset and width together can visibly drift while
                        // Android resizes the window surface asynchronously.
                        params.gravity = Gravity.TOP or Gravity.RIGHT
                        params.x = bounds.width() - (initialX + initialWidth)
                        params.width = resized.width
                        params.height = resized.height
                    } else {
                        params.gravity = Gravity.TOP or Gravity.LEFT
                        val size = FloatingBoardGeometry.resizedDimensions(
                            initialWidth = initialWidth,
                            initialHeight = initialHeight,
                            deltaX = deltaX,
                            deltaY = deltaY,
                            minimumWidth = dp(MIN_WIDTH_DP),
                            minimumHeight = dp(MIN_HEIGHT_DP),
                            maximumWidth = (bounds.width() - initialX).coerceAtLeast(1),
                            maximumHeight = (bounds.height() - initialY).coerceAtLeast(1)
                        )
                        params.x = initialX
                        params.width = size.width
                        params.height = size.height
                    }
                    params.y = initialY
                    updateBoardLayout()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun availableBoardBounds(): Rect {
        val manager = windowManager ?: return Rect(0, 0, 1, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = manager.maximumWindowMetrics
            val bounds = Rect(metrics.bounds)
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            bounds.set(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
            return bounds
        }
        val bounds = Rect()
        boardView?.getWindowVisibleDisplayFrame(bounds)
        if (bounds.isEmpty) {
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRectSize(bounds)
        }
        return bounds
    }

    // Android may have fitted a requested position to the display. Start each
    // gesture from the position actually shown, not the old requested offset.
    private fun synchronizeBoardPosition() {
        val view = boardView ?: return
        val params = layoutParams ?: return
        val bounds = availableBoardBounds()
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = location[0] - bounds.left
        params.y = location[1] - bounds.top
    }

    private fun updateBoardLayout() {
        val view = boardView ?: return
        val params = layoutParams ?: return
        val manager = windowManager ?: return
        val bounds = availableBoardBounds()
        params.width = params.width.coerceIn(1, bounds.width().coerceAtLeast(1))
        params.height = params.height.coerceIn(1, bounds.height().coerceAtLeast(1))
        params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
        try {
            manager.updateViewLayout(view, params)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Floating board was no longer attached", exception)
            stopAndCleanUp()
        }
    }

    private fun returnToFullScreen() {
        stopAndCleanUp()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    private fun stopAndCleanUp() {
        removeBoardWindow()
        if (foregroundStarted) {
            stopForeground(true)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun removeBoardWindow() {
        val view = boardView ?: return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Floating board was already removed", exception)
        } finally {
            boardView = null
            layoutParams = null
            windowManager = null
            windowContext = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.floating_board_notification_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.floating_board_notification_text)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val returnIntent = PendingIntent.getService(
            this,
            RETURN_REQUEST_CODE,
            controlIntent(ACTION_RETURN_TO_APP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            controlIntent(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(getString(R.string.floating_board_notification_title))
            .setContentText(getString(R.string.floating_board_notification_text))
            .setContentIntent(returnIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.return_to_easlie), returnIntent).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_floating_board), stopIntent).build())
            .build()
    }

    private fun controlIntent(action: String): Intent = Intent(this, FloatingBoardService::class.java).apply {
        this.action = action
    }

    private fun buttonLayoutParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        dp(48)
    )

    private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "FloatingBoardService"
        private const val NOTIFICATION_CHANNEL_ID = "floating_board"
        private const val NOTIFICATION_ID = 3
        private const val RETURN_REQUEST_CODE = 301
        private const val STOP_REQUEST_CODE = 302
        private const val DEFAULT_WIDTH_DP = 360
        private const val DEFAULT_HEIGHT_DP = 260
        private const val DEFAULT_X_DP = 24
        private const val DEFAULT_Y_DP = 72
        private const val MIN_WIDTH_DP = 280
        private const val MIN_HEIGHT_DP = 200

        const val ACTION_START = "com.chrisb588.easlie.action.START_FLOATING_BOARD"
        const val ACTION_RETURN_TO_APP = "com.chrisb588.easlie.action.RETURN_TO_APP"
        const val ACTION_STOP = "com.chrisb588.easlie.action.STOP_FLOATING_BOARD"
        const val EXTRA_START_RESULT_RECEIVER =
            "com.chrisb588.easlie.extra.START_RESULT_RECEIVER"
        const val EXTRA_RESULT_MESSAGE = "com.chrisb588.easlie.extra.RESULT_MESSAGE"
        const val RESULT_ATTACHED = 1
        const val RESULT_FAILED = 2

        fun startIntent(context: Context): Intent = Intent(context, FloatingBoardService::class.java).apply {
            action = ACTION_START
        }
    }
}
