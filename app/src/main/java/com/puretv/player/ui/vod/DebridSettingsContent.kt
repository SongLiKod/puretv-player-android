package com.puretv.player.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.ui.InputDialog
import com.puretv.player.core.ui.SectionHeader
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvTextButton
import com.puretv.player.data.debrid.DebridRepository
import com.puretv.player.data.debrid.DebridState
import com.puretv.player.data.debrid.DebridStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 拓展服务设置内容（需求 5.4.5：Real-Debrid / TorBox 高级拓展）：
 * Token 输入（InputDialog）+ 测试连接 + 状态指示（success/warning/error）。
 * 由设置中心「账号」分区直接嵌入调用。
 */
@HiltViewModel
class DebridViewModel @Inject constructor(
    private val debridRepository: DebridRepository,
) : ViewModel() {

    val realToken: StateFlow<String?> = debridRepository.realToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val torBoxToken: StateFlow<String?> = debridRepository.torBoxToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _realStatus = MutableStateFlow(DebridStatus())
    val realStatus: StateFlow<DebridStatus> = _realStatus

    private val _torBoxStatus = MutableStateFlow(DebridStatus())
    val torBoxStatus: StateFlow<DebridStatus> = _torBoxStatus

    init {
        // 已配置 Token 时提示用户可测试连接
        viewModelScope.launch {
            if (debridRepository.realTokenValue() != null) {
                _realStatus.value = DebridStatus(DebridState.NONE, "点击「测试连接」验证 Token")
            }
            if (debridRepository.torBoxTokenValue() != null) {
                _torBoxStatus.value = DebridStatus(DebridState.NONE, "点击「测试连接」验证 Token")
            }
        }
    }

    fun saveRealToken(token: String) {
        viewModelScope.launch {
            debridRepository.setRealToken(token)
            _realStatus.value = if (token.trim().isEmpty()) {
                DebridStatus()
            } else {
                DebridStatus(DebridState.NONE, "已保存，点击「测试连接」验证")
            }
        }
    }

    fun saveTorBoxToken(token: String) {
        viewModelScope.launch {
            debridRepository.setTorBoxToken(token)
            _torBoxStatus.value = if (token.trim().isEmpty()) {
                DebridStatus()
            } else {
                DebridStatus(DebridState.NONE, "已保存，点击「测试连接」验证")
            }
        }
    }

    fun testReal() {
        viewModelScope.launch {
            _realStatus.value = DebridStatus(DebridState.TESTING, "正在测试连接…")
            _realStatus.value = try {
                debridRepository.testReal()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                DebridStatus(DebridState.FAILED, "测试失败，请检查网络与 Token")
            }
        }
    }

    fun testTorBox() {
        viewModelScope.launch {
            _torBoxStatus.value = DebridStatus(DebridState.TESTING, "正在测试连接…")
            _torBoxStatus.value = try {
                debridRepository.testTorBox()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                DebridStatus(DebridState.FAILED, "测试失败，请检查网络与 Token")
            }
        }
    }
}

/**
 * 拓展服务（Debrid）设置内容块：Real-Debrid / TorBox 两组 Token 配置与连接测试。
 */
@Composable
fun DebridSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: DebridViewModel = hiltViewModel(),
) {
    val colors = PureTvTheme.colors
    val realToken by viewModel.realToken.collectAsStateWithLifecycle()
    val torBoxToken by viewModel.torBoxToken.collectAsStateWithLifecycle()
    val realStatus by viewModel.realStatus.collectAsStateWithLifecycle()
    val torBoxStatus by viewModel.torBoxStatus.collectAsStateWithLifecycle()

    var showRealInput by remember { mutableStateOf(false) }
    var showTorBoxInput by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = "拓展服务（Debrid）",
            subtitle = "配置后播放时可解析高清稳定直链",
            modifier = Modifier.padding(bottom = 8.dp),
        )

        TokenSettingRow(
            name = "Real-Debrid",
            description = "已配置 · 点击修改 Token",
            configured = !realToken.isNullOrBlank(),
            status = realStatus,
            onEdit = { showRealInput = true },
            onTest = viewModel::testReal,
        )

        HorizontalDivider(
            color = colors.divider,
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 16.dp),
        )

        TokenSettingRow(
            name = "TorBox",
            description = "已配置 · 点击修改 Token",
            configured = !torBoxToken.isNullOrBlank(),
            status = torBoxStatus,
            onEdit = { showTorBoxInput = true },
            onTest = viewModel::testTorBox,
        )

        Text(
            text = "说明：未配置时播放自动使用普通源；配置后详情页可用「Debrid 加速」解析直链。",
            color = colors.textHint,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (showRealInput) {
        InputDialog(
            title = "设置 Real-Debrid Token",
            label = "在 real-debrid.com 开发者页面生成的访问令牌",
            placeholder = "粘贴 Token",
            password = true,
            confirmText = "保存",
            initialValue = realToken.orEmpty(),
            onDone = { value ->
                showRealInput = false
                viewModel.saveRealToken(value)
            },
            onDismiss = { showRealInput = false },
        )
    }

    if (showTorBoxInput) {
        InputDialog(
            title = "设置 TorBox Token",
            label = "在 torbox.app 设置页生成的 API Key",
            placeholder = "粘贴 Token",
            password = true,
            confirmText = "保存",
            initialValue = torBoxToken.orEmpty(),
            onDone = { value ->
                showTorBoxInput = false
                viewModel.saveTorBoxToken(value)
            },
            onDismiss = { showTorBoxInput = false },
        )
    }
}

/** 单个拓展服务配置行：名称 + 配置状态 + 测试按钮 + 状态指示 */
@Composable
private fun ColumnScope.TokenSettingRow(
    name: String,
    description: String,
    configured: Boolean,
    status: DebridStatus,
    onEdit: () -> Unit,
    onTest: () -> Unit,
) {
    val colors = PureTvTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = colors.textPrimary,
                fontSize = 18.sp,
            )
            val statusColor = when {
                status.state == DebridState.OK -> colors.success
                status.state == DebridState.FAILED -> colors.error
                status.state == DebridState.TESTING -> colors.warning
                configured -> colors.textTertiary
                else -> colors.textHint
            }
            val statusText = when {
                status.message != null -> status.message
                configured -> description
                else -> "未配置 · 点击输入 Token"
            }
            Text(
                text = statusText.orEmpty(),
                color = statusColor,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TvTextButton(text = if (configured) "修改" else "输入", onClick = onEdit)
        TvButton(
            text = "测试连接",
            onClick = onTest,
            variant = TvButtonVariant.SECONDARY,
            enabled = configured && status.state != DebridState.TESTING,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
