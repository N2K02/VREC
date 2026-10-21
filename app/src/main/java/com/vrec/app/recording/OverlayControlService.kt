package com.vrec.app.recording

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small movable Pause/Resume + Stop control shown above other apps while VREC records.
 * The foreground RecordingService remains responsible for microphone ownership and persistence.
 */
class OverlayControlService : Service() {
    companion object {
        const val ACTION_SHOW = "com.vrec.app.overlay.SHOW"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var pauseButton: ImageButton? = null

    private val refresh = object : Runnable {
        override fun run() {
            val state = RecordingService.state.value
            if (state !is RecordingService.State.Recording && state !is RecordingService.State.Paused) {
                removeOverlay()
                stopSelf()
                return
            }
            render(state)
            handler.postDelayed(this, 1000L)
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
        val heading = TextView(this).apply {
            text = "●"
            textSize = 18f
            setTextColor(Color.RED)
            gravity = Gravity.CENTER
            contentDescription = "Drag recording controls"
        }
        val pause = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            contentDescription = "Pause recording"
            background = ColorDrawable(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                val action = if (RecordingService.state.value is RecordingService.State.Paused)
                    RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
                startService(Intent(this@OverlayControlService, RecordingService::class.java).setAction(action))
            }
        }
        val stop = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Stop recording"
            background = ColorDrawable(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                startService(Intent(this@OverlayControlService, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            }
        }
        panel.addView(heading, LinearLayout.LayoutParams(dp(32), dp(42)))
        panel.addView(pause, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(stop, LinearLayout.LayoutParams(dp(44), dp(44)))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(160)
        }
        heading.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var initialX = 0
            private var initialY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        initialX = lp.x
                        initialY = lp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (initialX - (event.rawX - downX).toInt()).coerceAtLeast(0)
                        lp.y = (initialY + (event.rawY - downY).toInt()).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(panel, lp) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })
        root = panel
        title = heading
        pauseButton = pause
        runCatching { windowManager.addView(panel, lp) }.onFailure {
            root = null
            title = null
            pauseButton = null
            stopSelf()
        }
    }

    private fun render(state: RecordingService.State) {
        when (state) {
            is RecordingService.State.Recording -> {
                title?.text = "●"
                pauseButton?.setImageResource(android.R.drawable.ic_media_pause)
                pauseButton?.contentDescription = "Pause recording"
            }
            is RecordingService.State.Paused -> {
                title?.text = "Ⅱ"
                pauseButton?.setImageResource(android.R.drawable.ic_media_play)
                pauseButton?.contentDescription = "Resume recording"
            }
            else -> Unit
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun removeOverlay() {
        handler.removeCallbacks(refresh)
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        title = null
        pauseButton = null
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
