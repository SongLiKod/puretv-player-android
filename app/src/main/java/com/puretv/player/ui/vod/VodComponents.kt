package com.puretv.player.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.puretv.player.core.theme.PureTvTheme
import com.puretv.player.core.ui.TvCard
import com.puretv.player.core.ui.isPortrait
import com.puretv.player.data.local.entity.VodItemEntity

/**
 * 海报墙通用组件（需求 5.2.5 / 5.4.1）：
 * 焦点 1.02 缩放 + 边框高亮（共享 TvCard），评分星标用全局警告橙，最小字号 14sp。
 */

/** 品类/分类筛选胶囊（焦点统一动效，左右键切换） */
@Composable
fun VodFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    TvCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (selected) colors.primary.copy(alpha = 0.18f) else Color.Transparent
                )
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (selected) colors.primary else colors.textSecondary,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 品类 Tab：全部/电影/电视剧/综艺/动漫（需求 5.4.1 全品类聚合分类） */
@Composable
fun VodKindTabs(
    selectedKind: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        item { VodFilterChip(text = "全部", selected = selectedKind == null, onClick = { onSelect(null) }) }
        item { VodFilterChip(text = "电影", selected = selectedKind == "movie", onClick = { onSelect("movie") }) }
        item { VodFilterChip(text = "电视剧", selected = selectedKind == "series", onClick = { onSelect("series") }) }
        item { VodFilterChip(text = "综艺", selected = selectedKind == "variety", onClick = { onSelect("variety") }) }
        item { VodFilterChip(text = "动漫", selected = selectedKind == "anime", onClick = { onSelect("anime") }) }
    }
}

/** Xtream 分类横滑列表（空列表时自动隐藏） */
@Composable
fun VodCategoryRow(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        item {
            VodFilterChip(
                text = "全部分类",
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(categories.size) { index ->
            val category = categories[index]
            VodFilterChip(
                text = category,
                selected = category == selected,
                onClick = { onSelect(category) },
            )
        }
    }
}

/** 评分星标（全局警告橙 #F97316，评分缺失显示「暂无评分」） */
@Composable
fun VodRating(
    rating: Double?,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    if (rating != null && rating > 0.0) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "★", color = colors.warning, fontSize = 15.sp)
            Text(
                text = String.format(java.util.Locale.US, "%.1f", rating),
                color = colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    } else {
        Text(
            text = "暂无评分",
            color = colors.textHint,
            fontSize = 14.sp,
            modifier = modifier,
        )
    }
}

/** 海报卡片：海报图 + 渐变遮罩信息条 + 评分/年份（焦点 1.02 缩放 + 边框高亮） */
@Composable
fun VodPosterCard(
    item: VodItemEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PureTvTheme.colors
    TvCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(colors.background),
                contentAlignment = Alignment.Center,
            ) {
                if (item.posterUrl.isNullOrBlank()) {
                    // 无海报时用主题色渐变占位 + 首字（禁止硬编码色值）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(
                                        colors.primary.copy(alpha = 0.22f),
                                        colors.card,
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.title.take(1),
                            color = colors.highlight,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 底部渐变遮罩：压住亮色海报保证文字可读
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        androidx.compose.ui.graphics.Color.Transparent,
                                        androidx.compose.ui.graphics.Color(0xCC0B0D14),
                                    ),
                                ),
                            ),
                    )
                }
                // 类型角标（电影/剧集）
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(androidx.compose.ui.graphics.Color(0x990B0D14))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = when (item.kind) {
                            com.puretv.player.data.model.VodKind.SERIES -> "剧集"
                            com.puretv.player.data.model.VodKind.VARIETY -> "综艺"
                            com.puretv.player.data.model.VodKind.ANIME -> "动漫"
                            else -> "电影"
                        },
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 12.sp,
                    )
                }
                // 海报底部：评分
                if (item.rating != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(androidx.compose.ui.graphics.Color(0x990B0D14))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = String.format("%.1f", item.rating),
                            color = colors.warning,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                Text(
                    text = item.title,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        item.year,
                        item.category?.takeIf { it != "未分类" },
                    ).joinToString(" · ").ifEmpty { "暂无信息" },
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** 类型标签 chip（详情页：动作 / 剧情 …） */
@Composable
fun GenreChip(text: String, modifier: Modifier = Modifier) {
    val colors = PureTvTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.card)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = colors.textSecondary,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/** 状态徽标（未看完=警告橙 / 有更新=成功绿，需求 5.4.3 智能追更标记） */
@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 背景大图 + 主题渐变遮罩（详情页顶部沉浸式背景；无背景图时不占高度，避免顶部空白块） */
@Composable
fun VodBackdrop(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    // 无背景图：不渲染固定高度的背景块，信息区（海报+片名）直接顶到标题栏下方
    if (url.isNullOrBlank()) return
    val colors = PureTvTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isPortrait()) 170.dp else 300.dp),
    ) {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 遮罩取主题 scrim 固定色（全局固定透明遮罩 #00000080），向背景色渐隐
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            colors.scrim.copy(alpha = 0.25f),
                            colors.scrim,
                            colors.background,
                        ),
                    )
                ),
        )
    }
}
