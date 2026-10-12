package com.puretv.player.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 全局通用固定色值（三套主题共用，不随主题切换）
 * 适用于按钮、状态提示、进度条、弹窗、操作状态，全主题统一，保证交互一致性
 */
val FixedSuccess = Color(0xFF22C55E) // 成功绿色：操作成功、开启状态、正常在线源
val FixedWarning = Color(0xFFF97316) // 警告橙色：网络波动、缓冲中、弱网提示
val FixedError = Color(0xFFEF4444)   // 错误红色：源失效、加载失败、权限异常、报错文字
val FixedLink = Color(0xFF3B82F6)    // 链接蓝色：可点击链接、帮助文档、版本更新
val FixedScrim = Color(0x80000000)   // 透明遮罩：弹窗背景、播放控件遮罩、焦点悬浮遮罩

/* ---------------------------- 深色主题（观影主打·默认首选） ---------------------------- */

// 4.1 全局背景体系
val DarkBackground = Color(0xFF0F1118) // 页面全局背景
val DarkCard = Color(0xFF1A1D29)       // 卡片模块背景：海报墙卡片、设置项卡片、EPG列表底色
val DarkDialog = Color(0xFF232736)     // 弹窗底部背景：弹窗、菜单、选择框
val DarkDivider = Color(0xFF2F3344)    // 分割线/边框

// 4.2 主题主辅色
val DarkPrimary = Color(0xFF3B82F6)    // APP主色调：按钮、确认键、选中状态、Tab选中
val DarkHighlight = Color(0xFF60A5FA)  // 辅助高亮色：焦点移动高亮、遥控器选中焦点、悬浮提示

// 4.3 文字层级颜色（四级文字）
val DarkTextPrimary = Color(0xFFFFFFFF)   // 一级标题文字：页面大标题、影片名称、频道名称、菜单主选项
val DarkTextSecondary = Color(0xFFE5E7EB) // 二级常规文字：简介内容、节目单信息、参数说明、列表正文
val DarkTextTertiary = Color(0xFF9CA3AF)  // 三级弱化文字：时间、热度、集数、版本号、次要备注
val DarkTextHint = Color(0xFF6B7280)      // 占位提示文字：空列表提示、输入框占位、无数据提示

// 4.4 焦点专属色（TV核心交互）
val DarkFocusBorder = Color(0xFF3B82F6)   // 焦点边框高亮
val DarkFocusPressed = Color(0xFF1D4ED8)  // 焦点按压加深

/* ---------------------------- 浅色主题（日间高亮模式） ---------------------------- */

// 5.1 全局背景体系
val LightBackground = Color(0xFFF9FAFB) // 页面全局背景
val LightCard = Color(0xFFFFFFFF)       // 卡片模块背景
val LightDialog = Color(0xFFF3F4F6)     // 弹窗底部背景
val LightDivider = Color(0xFFE5E7EB)    // 分割线/边框

// 5.2 主题主辅色
val LightPrimary = Color(0xFF3B82F6)    // APP主色调：与深色主题一致的品牌蓝
val LightHighlight = Color(0xFF2563EB)  // 辅助高亮色：浅色模式焦点高亮，对比度更高

// 5.3 文字层级颜色
val LightTextPrimary = Color(0xFF111827) // 一级标题文字
val LightTextSecondary = Color(0xFF374151) // 二级常规文字
val LightTextTertiary = Color(0xFF6B7280)  // 三级弱化文字
val LightTextHint = Color(0xFF9CA3AF)      // 占位提示文字

// 5.4 焦点专属色
val LightFocusBorder = Color(0xFF2563EB)  // 焦点边框高亮：高对比焦点框，远距离清晰可见
val LightFocusPressed = Color(0xFF1D4ED8) // 焦点按压加深
