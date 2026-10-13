package com.puretv.player.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.puretv.player.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 系统级弹窗播放悬浮层（追加需求）：
 * 通过 WindowManager 叠加 TYPE_APPLICATION_OVERLAY 悬浮窗，视频悬浮在
 * **桌面与其他应用之上**（缩小应用后仍在最上层继续播放）。
 *
 * - 复用单例播放内核同一 Player 实例：弹窗无二次起播、不丢进度；
 * - 拖动移动、点画面/「还原」拉起应用回播放页、× 关闭并停止；
 * - 播放停止（无播放任务）或退出弹窗状态时自动移除悬浮窗；
 * - 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW），未授权时由播放页引导申请。
 */
@Singleton
class SystemFloatingWindowManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: PlaybackEngine,
    private val controller: FloatingPlayerController,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var container: FrameLayout? = null
    private var playButton: ImageView? = null
    private var attachedPlayer: Player? = null
    private var playerListener: Player.Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // 进入弹窗状态 → 添加系统悬浮窗；退出（还原/关闭）→ 移除
        scope.launch {
            controller.isFloating.collect { floating ->
                if (floating) show() else hide()
            }
        }
        // 播放停止/任务清空 → 自动收起
        scope.launch {
            engine.currentRequest.collect { request -> if (request == null) hide() }
        }
        // 底层播放器实例重建后重新绑定（与播放页同款轮询思路）；
        // 进入弹窗瞬间播放器未就绪导致 show 失败时，这里自动重试补齐悬浮窗
        scope.launch {
            while (true) {
                delay(400)
                val c = container
                if (c == null) {
                    if (controller.isFloating.value) show()
                    continue
                }
                val player = (engine as? PlayerProvider)?.player
                if (player != null && player !== attachedPlayer) attachPlayer(c, player)
            }
        }
    }

    /** 显示悬浮窗（需已授权且内核已创建播放器，否则返回 false） */
    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (container != null) return true
        if (!Settings.canDrawOverlays(context)) return false
        val player = (engine as? PlayerProvider)?.player ?: return false

        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

        val playerView = PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }

        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(dp(WIDTH_DP), dp(HEIGHT_DP)),
        )

        // 控制按钮行（右上角）：播放/暂停 · 关闭；其余区域点按 = 还原回播放页
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val play = circleButton(android.R.drawable.ic_media_play) { engine.playPause() }
        val close = circleButton(android.R.drawable.ic_menu_close_clear_cancel) {
            controller.exit()
            engine.stop()
        }
        playButton = play
        controls.addView(play)
        controls.addView(close)
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(4); rightMargin = dp(4) },
        )

        val params = WindowManager.LayoutParams(
            dp(WIDTH_DP),
            dp(HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // FLAG_NOT_FOCUSABLE：窗口外触摸事件透传给下层应用；KEEP_SCREEN_ON：播放中常亮
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(12)
            y = dp(96)
        }

        setupTouch(root, params)

        attachPlayer(root, player)
        // 防御：极端场景（窗口服务异常/权限刚被回收）下 addView 可能抛错，失败则不残留状态
        runCatching { windowManager.addView(root, params) }.onFailure {
            playerListener?.let { l -> player.removeListener(l) }
            attachedPlayer = null
            playerListener = null
            playButton = null
            return false
        }
        container = root
        return true
    }

    /** 移除悬浮窗 */
    fun hide() {
        val root = container ?: return
        runCatching { windowManager.removeView(root) }
        playerListener?.let { l -> attachedPlayer?.removeListener(l) }
        attachedPlayer = null
        playerListener = null
        playButton = null
        container = null
    }

    /** 绑定播放器实例并监听播放态刷新按钮图标 */
    private fun attachPlayer(root: FrameLayout, player: Player) {
        playerListener?.let { l -> attachedPlayer?.removeListener(l) }
        (root.getChildAt(0) as? PlayerView)?.player = player
        attachedPlayer = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton?.setImageResource(
                    if (isPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                )
            }
        }
        playerListener = listener
        player.addListener(listener)
        playButton?.setImageResource(
            if (player.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
        )
    }

    /** 拖动移动 + 松手未拖动 = 点按还原 */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch(root: View, params: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = context.resources.displayMetrics.density * 8f
        root.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        // BOTTOM|END 重力：x 正方向向左、y 正方向向上，与屏幕坐标符号相反
                        params.x = startX - dx.toInt()
                        params.y = startY - dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }

                android.view.MotionEvent.ACTION_UP -> {
                    if (!dragging) expand()
                    true
                }

                else -> false
            }
        }
    }

    /** 悬浮窗「还原」：收起悬浮层并拉起应用回播放页全屏接管 */
    private fun expand() {
        controller.requestExpandToPlayer()
        controller.exit()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = FloatingPlayerController.ACTION_EXPAND
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        context.startActivity(intent)
    }

    private fun circleButton(iconRes: Int, onClick: () -> Unit): ImageView {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
        return ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            val pad = dp(5)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 0, 0, 0))
                shape = GradientDrawable.OVAL
            }
            val size = dp(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) }
            setOnClickListener { onClick() }
        }
    }

    companion object {
        private const val WIDTH_DP = 172
        private const val HEIGHT_DP = 100
    }
}
