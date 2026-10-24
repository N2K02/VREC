package com.vrec.app.recording

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.vrec.app.R

/**
 * Movable recording controls shown above other apps while VREC records.
 *
 * Drag the status dot anywhere within the display. The dot blinks red while recording,
 * stays orange while paused, and turns green while the recording is stopping.
 */
class OverlayControlService : Service() {
    companion object {
        const val ACTION_SHOW = "com.vrec.app.overlay.SHOW"
        private const val STOPPING_VISIBLE_MS = 800L
        private const val STOPPING_TIMEOUT_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var statusDot: TextView? = null
    private var pauseButton: ImageButton? = null
    private var stopButton: ImageButton? = null
    private var blinkAnimator: ObjectAnimator? = null
    private var stoppingSince: Long? = null

    private val refresh = object : Runnable {
        override fun run() {
            val state = RecordingService.state.value
            val stoppingAt = stoppingSince
            if (stoppingAt != null) {
                val elapsed = SystemClock.elapsedRealtime() - stoppingAt
                if (state is RecordingService.State.Idle && elapsed >= STOPPING_VISIBLE_MS) {
                    removeOverlay()
                    stopSelf()
                    return
                }
                if (state !is RecordingService.State.Idle && elapsed >= STOPPING_TIMEOUT_MS) {
                    stoppingSince = null
                    render(state)
                } else {
                    renderStopping()
                }
                handler.postDelayed(this, 100L)
                return
            }

            if (state !is RecordingService.State.Recording && state !is RecordingService.State.Paused) {
                removeOverlay()
                stopSelf()
                return
            }
            render(state)
            handler.postDelayed(this, 500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_SHOW) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) createOverlay()
        if (root == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        return START_NOT_STICKY
    }

    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                setColor(0xF2222222.toInt())
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0xFF666666.toInt())
            }
            elevation = dp(8).toFloat()
        }
        val status = TextView(this).apply {
            text = "●"
            textSize = 22f
            setTextColor(Color.RED)
            gravity = Gravity.CENTER
            contentDescription = "Recording. Drag controls"
        }
        val pause = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            contentDescription = "Pause recording"
            background = ColorDrawable(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                if (stoppingSince != null) return@setOnClickListener
                val action = if (RecordingService.state.value is RecordingService.State.Paused) {
                    RecordingService.ACTION_RESUME
                } else {
                    RecordingService.ACTION_PAUSE
                }
                startService(Intent(this@OverlayControlService, RecordingService::class.java).setAction(action))
            }
        }
        val stop = ImageButton(this).apply {
            setImageResource(R.drawable.ic_stop)
            contentDescription = "Stop recording"
            background = ColorDrawable(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                if (stoppingSince != null) return@setOnClickListener
                stoppingSince = SystemClock.elapsedRealtime()
                renderStopping()
                startService(
                    Intent(this@OverlayControlService, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_STOP),
                )
                handler.removeCallbacks(refresh)
                handler.post(refresh)
            }
        }

        panel.addView(status, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(pause, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(stop, LinearLayout.LayoutParams(dp(44), dp(44)))

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        status.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var initialX = 0
            private var initialY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        initialX = layout.x
                        initialY = layout.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val (screenWidth, screenHeight) = screenSize()
                        val maxX = (screenWidth - panel.width).coerceAtLeast(0)
                        val maxY = (screenHeight - panel.height).coerceAtLeast(0)
                        layout.x = (initialX + (event.rawX - downX).toInt()).coerceIn(0, maxX)
                        layout.y = (initialY + (event.rawY - downY).toInt()).coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(panel, layout) }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.performClick()
                        return true
                    }
                }
                return false
            }
        })

        root = panel
        statusDot = status
        pauseButton = pause
        stopButton = stop
        runCatching { windowManager.addView(panel, layout) }.onFailure {
            root = null
            statusDot = null
            pauseButton = null
            stopButton = null
            stopSelf()
        }
    }

    private fun render(state: RecordingService.State) {
        if (stoppingSince != null) {
            renderStopping()
            return
        }
        when (state) {
            is RecordingService.State.Recording -> {
                statusDot?.apply {
                    text = "●"
                    setTextColor(Color.RED)
                    contentDescription = "Recording. Drag controls"
                }
                startBlinking()
                pauseButton?.apply {
                    isEnabled = true
                    alpha = 1f
                    setImageResource(android.R.drawable.ic_media_pause)
                    contentDescription = "Pause recording"
                }
                stopButton?.apply {
                    isEnabled = true
                    alpha = 1f
                }
            }
            is RecordingService.State.Paused -> {
                stopBlinking()
                statusDot?.apply {
                    text = "●"
                    setTextColor(0xFFFF9800.toInt())
                    contentDescription = "Recording paused. Drag controls"
                }
                pauseButton?.apply {
                    isEnabled = true
                    alpha = 1f
                    setImageResource(android.R.drawable.ic_media_play)
                    contentDescription = "Resume recording"
                }
                stopButton?.apply {
                    isEnabled = true
                    alpha = 1f
                }
            }
            else -> Unit
        }
    }

    private fun renderStopping() {
        stopBlinking()
        statusDot?.apply {
            text = "●"
            setTextColor(0xFF4CAF50.toInt())
            contentDescription = "Stopping recording. Drag controls"
        }
        pauseButton?.apply {
            isEnabled = false
            alpha = 0.45f
        }
        stopButton?.apply {
            isEnabled = false
            alpha = 0.45f
        }
    }

    private fun startBlinking() {
        val dot = statusDot ?: return
        if (blinkAnimator?.isRunning == true) return
        blinkAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 650L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopBlinking() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        statusDot?.alpha = 1f
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.run { widthPixels to heightPixels }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun removeOverlay() {
        handler.removeCallbacks(refresh)
        stopBlinking()
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        statusDot = null
        pauseButton = null
        stopButton = null
        stoppingSince = null
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
