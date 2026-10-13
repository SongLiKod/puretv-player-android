package com.puretv.player.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弹窗播放（系统级悬浮窗）状态（追加需求：播放界面点「弹窗」后视频缩小为悬浮小窗，
 * 悬浮在桌面与其他应用之上继续播放；点小窗还原回播放页，× 关闭）。
 *
 * Hilt 单例：播放页、系统悬浮层（[com.puretv.player.player.SystemFloatingWindowManager]）、
 * MainActivity（expand 回跳）跨组件共享；播放内核为单例，小窗直接复用同一播放实例，无二次起播。
 */
@Singleton
class FloatingPlayerController @Inject constructor() {

    private val _isFloating = MutableStateFlow(false)

    /** 是否处于弹窗播放（true 时系统悬浮层接管画面，播放页退出不释放内核） */
    val isFloating: StateFlow<Boolean> = _isFloating

    /** 悬浮窗「还原」事件：拉起应用并跳转播放页全屏接管 */
    private val _expandToPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expandToPlayer: SharedFlow<Unit> = _expandToPlayer

    /** 悬浮窗权限申请中标记（点了弹窗按钮但尚未授权，返回应用后继续弹窗流程） */
    private val _permissionPending = MutableStateFlow(false)

    fun enter() {
        _isFloating.value = true
    }

    fun exit() {
        _isFloating.value = false
    }

    fun requestExpandToPlayer() {
        _expandToPlayer.tryEmit(Unit)
    }

    fun markPermissionPending() {
        _permissionPending.value = true
    }

    fun consumePermissionPending(): Boolean {
        val pending = _permissionPending.value
        _permissionPending.value = false
        return pending
    }

    companion object {
        /** 悬浮窗「还原」拉起应用的 action（singleTask → onNewIntent 接收） */
        const val ACTION_EXPAND = "com.puretv.player.action.EXPAND_FLOAT"
    }
}
