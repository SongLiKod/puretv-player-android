package com.puretv.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.puretv.player.BuildConfig
import com.puretv.player.core.navigation.Routes
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled

/** 开源依赖清单（技术文档 2.2 选型，均为 Apache-2.0 许可） */
private val OSS_LIBRARIES = listOf(
    "Jetpack Compose（UI 框架）" to "AndroidX / Apache-2.0",
    "Material 3 与 TV Material（大屏组件）" to "AndroidX / Apache-2.0",
    "AndroidX Media3 / ExoPlayer（播放内核）" to "AndroidX / Apache-2.0",
    "Coil（图片加载）" to "Apache-2.0",
    "Moshi（JSON 序列化）" to "Square / Apache-2.0",
    "Dagger Hilt（依赖注入）" to "Google / Apache-2.0",
    "OkHttp + Retrofit（网络层）" to "Square / Apache-2.0",
    "Room（本地数据库）" to "AndroidX / Apache-2.0",
    "DataStore（偏好持久化）" to "AndroidX / Apache-2.0",
    "Kotlin Coroutines + Flow（异步）" to "JetBrains / Apache-2.0",
)

/**
 * 关于分区（需求 5.8 纯净承诺 / 5.6.4 教程与源检测入口 / 版本与开源许可）。
 */
@Composable
fun AboutSection(
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingGroup(title = "版本信息") {
            SettingRow(
                title = "应用版本",
                value = "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                valueColor = colors.textSecondary,
            )
            InfoLine(text = "PureTV 极简影音 · PureTV Player · 包名 com.puretv.player")
        }

        SettingGroup(title = "纯净承诺", subtitle = "需求 5.8：核心影音功能 100% 永久免费") {
            InfoLine(
                text = "· 直播、点播、EPG、海报墙、解码等核心影音功能全部永久免费；",
            )
            InfoLine(
                text = "· 无开屏广告、无弹窗广告、无插播广告、无后台推送广告；",
            )
            InfoLine(
                text = "· 不做设备绑定、不限设备数量（5.1.3）；增值付费仅针对高级自定义皮肤、" +
                    "专属云端空间、无水印美化，且不影响基础使用。",
            )
        }

        SettingGroup(title = "帮助与工具") {
            SettingRow(
                title = "使用教程与常见问题",
                subtitle = "遥控器操作速查表 + 常见故障自助修复（5.6.4）",
                value = "进入",
                onClick = { nav.navigate(Routes.TUTORIAL) },
            )
            SettingRow(
                title = "源检测工具",
                subtitle = "检测源可达性、频道数、EPG 与抽样播放地址（5.6.4）",
                value = "进入",
                onClick = { nav.navigate(Routes.SOURCE_CHECK) },
            )
            SettingRow(
                title = "插件市场",
                subtitle = "字幕增强 · 资源聚合 · 界面美化（专业模式，5.7.1）",
                value = "进入",
                onClick = { nav.navigate(Routes.PLUGINS) },
            )
        }

        SettingGroup(title = "开源许可", subtitle = "本应用基于以下开源项目构建（Apache-2.0，除非另有说明）") {
            OSS_LIBRARIES.forEach { (name, license) ->
                Text(
                    text = "$name —— $license",
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        SettingGroup(title = "文档说明") {
            InfoLine(
                text = "产品需求与技术设计文档见仓库 docs/PureTV_Player_文档.md（上篇 PRD + 下篇 UI 主题与技术设计）；" +
                    "自定义皮肤仅开放主色与高亮色，遵循「全局仅蓝白灰黑」配色规范（7.3）。",
            )
        }
    }
}
