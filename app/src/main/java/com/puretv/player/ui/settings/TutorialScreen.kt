package com.puretv.player.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.EmptyState
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvScaffold

/** 遥控器操作速查（需求 5.2.2：上下换台、左右切分类、OK 确认、返回退出、菜单统一） */
private val REMOTE_KEYS = listOf(
    "上 / 下" to "直播换台、列表上下移动、焦点纵向移动",
    "左 / 右" to "切换分类 / 分区、焦点横向移动",
    "OK（确认）" to "播放选中的频道或影片、确认当前操作",
    "返回" to "返回上级页面；播放页内呼出退出确认",
    "菜单（≡）" to "呼出当前项操作菜单：收藏、上移下移、删除",
    "长按 OK" to "在收藏/记录列表可直接呼出操作菜单",
)

/** 分步图文教程 */
private val STEPS = listOf(
    Step(
        "第 1 步 · 导入直播源",
        "设置中心 → 源管理：支持 M3U 网址、本地文件、Xtream Codes、TVBox 聚合配置四种方式；" +
            "零基础可先「重新导入内置源」，内置 10 条高清样片 + 20 条 TVBox 聚合影视线路可直接体验。" +
            "新手引导页也提供「一键初始化」。",
    ),
    Step(
        "第 2 步 · 直播换台",
        "首页 → 直播专区：左/右切换分组，上/下换台，OK 进入全屏播放；" +
            "收藏频道后会在直播页置顶，清晰度按频道记忆（5.3.3）。",
    ),
    Step(
        "第 3 步 · 点播播放",
        "首页 → 影视点播：海报墙选择影片 → OK 进入详情 → 选集播放；" +
            "未看完的剧集会记录集数与进度，返回详情页可断点续播（5.4.3）。",
    ),
    Step(
        "第 4 步 · 倍速与字幕",
        "播放页呼出控制条：左右拖动进度、上下调节音量，可切换倍速（0.5x~2.0x）、" +
            "切换音轨与字幕轨、加载外挂字幕、调节画面比例（5.5.4）。",
    ),
    Step(
        "第 5 步 · 收藏与记录",
        "我的收藏与播放记录支持菜单键操作：上移/下移调整收藏顺序、取消收藏、" +
            "删除单条记录或清空全部；播放记录按最近观看排序并显示已看百分比。",
    ),
)

/** 常见问题（需求 5.6.4：常见问题修复） */
private val FAQ = listOf(
    FaqItem(
        "画面黑屏但有声音",
        "多为视频轨解码不兼容。请到设置 → 播放：切换「硬件加速解码」开关（关闭后走软解兜底），" +
            "或把缓冲档位调到「大」；若源本身只有音频轨，换其他频道验证。",
    ),
    FaqItem(
        "有画面但没声音",
        "先确认设备音量与静音状态；播放页呼出控制条切换音轨；" +
            "若该片源音轨编码特殊（如 EAC3），尝试切换硬件解码开关或更换播放源。",
    ),
    FaqItem(
        "播放卡顿、频繁缓冲",
        "弱网环境建议：设置 → 播放 → 缓冲档位调「大」，开启「卡顿自动换备用源」与「清晰度智能切换」；" +
            "也可在播放页手动降低清晰度。使用有线网络或 5GHz Wi-Fi 更稳定。",
    ),
    FaqItem(
        "EPG 节目单空白",
        "EPG 依赖源提供 XMLTV 地址。请确认源带有 EPG 地址（添加 M3U 源时可填写 EPG 地址）；" +
            "部分公共源不提供节目单，此时节目单区域会显示提示，属正常现象。",
    ),
    FaqItem(
        "找不到某个频道",
        "在直播页顶部搜索框输入频道关键字；确认该频道所属源处于「已启用」状态；" +
            "仍找不到可到设置 → 源管理「刷新」该源，或使用「源检测工具」检查源是否失效。",
    ),
    FaqItem(
        "导入/备份提示权限被拒",
        "应用使用系统文件选择器（SAF）访问文件，无需存储权限。" +
            "若提示无法读写，请重新选择一次文件/目录；使用 U 盘时确认设备已正确挂载，" +
            "并避免在系统升级过程中操作。",
    ),
    FaqItem(
        "源检测显示不可达",
        "先检查网络与代理设置；确认源地址可在浏览器打开；" +
            "部分源需要正确的用户名/密码（Xtream）或需在同一路由器网络下访问。" +
            "可在源管理中删除失效源，或导入新的源。",
    ),
)

private data class Step(val title: String, val body: String)

private data class FaqItem(val question: String, val answer: String)

/**
 * 教程与 FAQ（需求 5.6.4：内置使用教程、常见问题修复、源检测工具）。
 */
@Composable
fun TutorialScreen(nav: NavHostController) {
    val colors = PureTvTheme.colors
    TvScaffold(
        title = "使用教程",
        subtitle = "遥控器操作速查 · 分步图文 · 常见问题",
        onBack = { nav.popBackStack() },
        actions = {
            com.puretv.player.core.ui.TvButton(
                text = "源检测工具",
                onClick = { nav.navigate(Routes.SOURCE_CHECK) },
                variant = com.puretv.player.core.ui.TvButtonVariant.SECONDARY,
            )
        },
    ) {
        val portrait = com.puretv.player.core.ui.isPortrait()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (portrait) 16.dp else 40.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            SettingGroup(title = "遥控器操作速查", subtitle = "全应用统一的按键逻辑（5.2.2）") {
                REMOTE_KEYS.forEach { (key, desc) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.bodyMedium.scaled(),
                            color = colors.highlight,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 18.dp),
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.labelMedium.scaled(),
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            SettingGroup(title = "分步图文教程", subtitle = "从零开始，5 步用起来") {
                STEPS.forEach { step ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.bodyMedium.scaled(),
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = step.body,
                            style = MaterialTheme.typography.labelMedium.scaled(),
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            SectionTitle(text = "常见问题（点按展开）")
            if (FAQ.isEmpty()) {
                EmptyState(text = "暂无常见问题")
            } else {
                FAQ.forEach { item ->
                    FaqCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.scaled(),
        color = PureTvTheme.colors.textPrimary,
        fontWeight = FontWeight.Medium,
    )
}

/** FAQ 折叠卡片：OK 展开/收起答案 */
@Composable
private fun FaqCard(item: FaqItem) {
    val colors = PureTvTheme.colors
    var expanded by remember { mutableStateOf(false) }
    TvCard(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.question,
                    style = MaterialTheme.typography.bodyLarge.scaled(),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.link,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = item.answer,
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
