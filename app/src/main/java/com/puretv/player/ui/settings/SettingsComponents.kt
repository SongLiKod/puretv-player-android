package com.puretv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.theme.scaled
import com.puretv.player.core.ui.TvButton
import com.puretv.player.core.ui.TvSwitch
import com.puretv.player.core.ui.TvButtonVariant
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.TvDialog

/**
 * 设置分组卡片（iOS 分组列表风格）：紧凑灰色小节头（副说明右对齐灰字）+ 圆角卡片内的等高设置行。
 * 行与行之间以统一内边距形成节奏，避免标题/副标题块占掉过多纵向空间。
 */
@Composable
fun SettingGroup(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PureTvTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.scaled(),
                color = colors.textSecondary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textTertiary,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.card)
                .border(1.dp, colors.divider, RoundedCornerShape(14.dp))
                .padding(vertical = 4.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/** 设置行：标题 + 说明 + 右侧取值/控件；可点击行带完整焦点（TV 遥控器 OK 确认）与 iOS 式右箭头 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    if (onClick != null) {
        TvCard(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            borderWidth = 2.dp,
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowContent(title, subtitle, value, valueColor, trailing, showChevron = trailing == null)
            }
        }
    } else {
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RowContent(title, subtitle, value, valueColor, trailing, showChevron = false)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RowContent(
    title: String,
    subtitle: String?,
    value: String?,
    valueColor: Color?,
    trailing: (@Composable (RowScope.() -> Unit))?,
    showChevron: Boolean,
) {
    val colors = PureTvTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.scaled(),
                color = colors.textPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium.scaled(),
                    color = colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.scaled(),
                color = valueColor ?: colors.textSecondary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.padding(start = 2.dp).size(18.dp),
            )
        }
        if (trailing != null) {
            Row(
                modifier = Modifier.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
    }
}

/** 开关设置行：整行可聚焦，OK 切换（开=主色，关=分割线色） */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailing = { TvSwitch(checked = checked, enabled = enabled) },
    )
}

/** （TvSwitch 已统一收编到 core/ui/TvComponents.kt） */

/**
 * 选项选择行：右侧显示当前取值，OK 弹出选择弹窗（TV 焦点友好，避免行内横向焦点跳转）。
 * @param options 取值 key 到展示文案的映射（保持顺序）
 */
@Composable
fun OptionSelectorRow(
    title: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    extraNote: String? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selectedKey }?.second ?: selectedKey
    SettingRow(
        title = title,
        modifier = modifier,
        subtitle = if (extraNote != null) listOfNotNull(subtitle, extraNote).joinToString(" · ") else subtitle,
        value = currentLabel,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TvDialog(onDismiss = { showDialog = false }, title = title) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { (key, label) ->
                    val selected = key == selectedKey
                    TvCard(
                        onClick = {
                            showDialog = false
                            if (!selected) onSelect(key)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = 2.dp,
                                        color = if (selected) PureTvTheme.colors.primary
                                        else PureTvTheme.colors.divider,
                                        shape = CircleShape,
                                    ),
                            ) {
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .padding(3.dp)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(PureTvTheme.colors.primary),
                                    )
                                }
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge.scaled(),
                                color = PureTvTheme.colors.textPrimary,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
                TvButton(
                    text = "关闭",
                    onClick = { showDialog = false },
                    variant = TvButtonVariant.GHOST,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
