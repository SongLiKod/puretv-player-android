# PureTV Player · PureTV 极简影音

> Android TV 原生影音播放器：直播 IPTV + 影视点播 + 本地影音一体化。
> 仓库：puretv-player-android ｜ 包名：`com.puretv.player`

严格按 `docs/PureTV_Player_文档.md`（上篇 PRD + 下篇 UI主题&技术设计文档）实现。

## 构建

```bash
./gradlew :app:assembleDebug          # 调试包
./gradlew :app:assembleRelease        # 混淆+资源收缩
```

- 构建环境：JDK 17、Gradle 8.9（wrapper）、AGP 8.7.3、Kotlin 2.0.21
- SDK：compileSdk 35 / **minSdk 21**（Android 5.0，兼容老旧盒子）/ **targetSdk 34**
- 本机为 aarch64，`gradle.properties` 已配置 `android.aapt2FromMavenOverride`（qemu shim）跑 x86_64 版 aapt2

## 技术栈（对应技术文档 2.2）

| 分类 | 选型 |
|---|---|
| 语言 | Kotlin（主）/ XML 资源 / C-C++（FFmpeg JNI 预留，见下） |
| UI | Jetpack Compose + Material3 + **Jetpack Compose TV（tv-material）**，单 Activity |
| 架构组件 | Lifecycle / ViewModel / Navigation-Compose |
| 播放内核 | **ExoPlayer（Media3 1.4.1）**：HLS/DASH、硬解 4K/HEVC、解码回退软解兜底 |
| 网络 | OkHttp + Retrofit（源拉取、EPG 下载、TMDB 刮削） |
| 异步 | Kotlin Coroutines + Flow（大列表解析不卡 UI） |
| 数据库 | Room（频道、播放记录、收藏、EPG 缓存、点播缓存、插件状态） |
| 持久化 | DataStore（主题、双模式、播放参数、API Key 等配置项） |
| 图片 | Coil（海报墙、台标加载缓存） |
| JSON | Moshi（codegen） |
| 依赖注入 | Dagger Hilt |

## 主题系统（技术文档三～八章）

- 枚举：`ThemeMode.THEME_SYSTEM / THEME_LIGHT / THEME_DARK`（跟随系统为出厂默认）
- 深色（观影主打）与浅色两套完整色值 + 三套主题共用固定色（成功 #22C55E、警告 #F97316、错误 #EF4444、链接 #3B82F6、遮罩 #00000080）
- 文字四级层级、焦点专属色（边框高亮 / 按压加深）、卡片焦点 1.02 缓动缩放 + 边框双重提示
- 设置中心可调：字体大小（≥14sp 下限）、焦点样式、界面布局密度、自定义皮肤（仅主色/高亮色，守卫"全局仅蓝白灰黑"）
- DataStore 驱动 + 监听系统夜间模式，实时换肤无需重启

## 代码地图

```
app/src/main/java/com/puretv/player/
├── PureTvApplication.kt / MainActivity.kt / MainViewModel.kt
├── core/
│   ├── theme/       # 三套主题色值、ThemeMode、字体规范、focusStyle
│   ├── ui/          # TvCard/TvButton/TvDialog/LoadingOverlay/TvScaffold 等统一焦点组件
│   ├── navigation/  # Routes 路由表 + AppNavHost
│   └── util/        # TimeUtils、StringUtils（回看占位符替换等）
├── data/
│   ├── local/       # Room：实体、DAO、Converters、PureTvDatabase
│   ├── prefs/       # DataStore：AppSettings + SettingsRepository
│   ├── model/       # ImportProgress、SourceCheckReport、常量
│   ├── repository/  # Favorites / History / Source(接口) / Epg 仓库
│   └── source/      # M3U、Xtream、XMLTV 解析与源检测
├── player/          # PlaybackEngine（Media3 内核）、PlayerRequests 统一播放封装
├── reminder/        # 节目预约 AlarmManager + 通知
├── di/              # Hilt：AppModule / DatabaseModule / PlayerModule / SourceModule...
└── ui/
    ├── home/        # 首页六大模块（直播专区/影视点播/本地影音/我的收藏/播放记录/设置中心）
    ├── guide/       # 新手引导（一键导入源、一键初始化）
    ├── live/        # 直播：分组栏、频道列表+进度解析、EPG 时间轴、四分屏、回看、预约
    ├── vod/         # 点播：海报墙、详情、分集、TMDB 刮削、Debrid 拓展
    ├── local/       # 本地影音：MediaStore 扫描、权限引导
    ├── favorites/   # 我的收藏（置顶排序）
    ├── history/     # 播放记录（断点续播、未看完标记）
    ├── player/      # 播放页：倍速/字幕/音轨/画面比例/换台抽屉
    ├── settings/    # 设置中心、源管理导入导出、云同步、教程 FAQ、源检测工具
    └── plugins/     # 插件市场（官方审核）
```

## 需求实现对照

## 需求实现对照

| 文档条款 | 实现位置 |
|---|---|
| **5.1 设备适配** | minSdk 21 / leanback / TV banner / 单 Activity / 播放器退出即释放 |
| 5.2.1–5.2.2 TV 适配与遥控器 | `core/ui`（统一焦点边框+1.02 缩放+180ms 缓动）、全页面 D-pad 上下左右/OK/返回 |
| 5.2.3 皮肤/字体/焦点样式/布局 | `ui/settings/AppearanceSection` + `SettingsRepository`（DataStore 实时生效） |
| 5.2.4 首页六大模块 | `ui/home`（直播专区/影视点播/本地影音/我的收藏/播放记录/设置中心） |
| 5.2.5 海报墙 | `ui/vod/VodScreen`（海报+评分+年份+焦点缩放）、`VodDetailScreen`（背板+简介+演员+分集） |
| **5.3.1 M3U/Xtream/TVBox 协议** | `data/source/M3uParser`（EXTINF 属性/EXTGRP/VLC/KODIPROP/备用源）、`XtreamClient`、`data/tvbox/TvBoxConfigImporter`（TVBox 配置 sites/lives/parses 解析，MacCMS 采集站→点播源、lives→直播源，jar 爬虫站点提示不支持） |
| 5.3.2 大容量秒解析+进度 | 逐行流式解析、每 500 条分块入库、`ImportProgress` 百分比回调、可取消 |
| 5.3.3 分组/收藏置顶/清晰度记忆/换台提速 | `LiveViewModel`（Room Flow 秒开、切台仅 `engine.play`）、`ChannelSettingDao` |
| 5.3.4 EPG/时间轴/四分屏/回看/预约 | `XmlTvParser`+`EpgRepository`、`ProgramGuideDialog`、`MosaicScreen`（4×独立播放器仅焦点格有声）、`ReminderScheduler`+`ReminderReceiver` |
| 5.3.5 直播低延迟/智能缓冲 | 见 5.5.3（内核层实现） |
| **5.4.1 全品类聚合** | `data/vod/VodRepository`（电影/剧集/综艺/动漫；双协议内容源：Xtream `player_api.php` + MacCMS 采集 `api.php/provide/vod`，`CmsVodClient` 解析 `vod_play_from`/`vod_play_url` 分集） |
| 5.4.2 TMDB 刮削 | `TmdbClient`+`TmdbRepository`（海报/评分/简介/演员/分集，中文优先，未配 Key 静默降级） |
| 5.4.3 智能追更/断点续播 | `VodDetailViewModel`（看到第 N 集/未看完高亮/有更新角标）、播放内核 5s 落进度 |
| 5.4.4 清晰度智能切换 | `PlaybackEngineImpl`（弱网降 1080P，平稳 30s 恢复 4K） |
| 5.4.5 Real-Debrid/TorBox | `data/debrid/*` + 设置中心「拓展服务」+ 详情页 Debrid 加速 |
| **5.5.1 格式兼容** | `PlaybackRenderersFactory`（硬解优先+软解回退）、HLS/DASH/MP4/MKV/TS/FLV |
| 5.5.2 4K/HEVC 硬解 | Media3 硬解优先、qualityLabel 4K/1080P/720P/SD |
| 5.5.3 智能缓冲/自动换源 | `LoadControl` 四档（小/中/大/自定义）、弱网标记、stallCount≥2 自动切备用源 |
| 5.5.4 倍速/字幕/音轨/画面比例 | `PlaybackScreen` 工具栏（0.5–2.0 倍速、网络字幕加载、音轨单选、6 种比例） |
| 5.5.5 本地+网络双优化 | `ui/local`（MediaStore 扫描、API29 分界缩略图、权限引导）、网络走 OkHttp |
| **5.6.1 新手引导** | `ui/guide`（一键初始化=安装内置 10 条高清样片 + 10 条 TVBox 聚合影视线路、一键导入源） |
| 5.6.2–5.6.3 极简/专业双模式 | `UiMode.SIMPLE/PRO`，极简隐藏复杂项 |
| 5.6.4 教程/FAQ/源检测 | `TutorialScreen`（操作速查+FAQ）+`SourceCheckScreen`（可达性/延迟/频道数/EPG/抽样播放健康度） |
| **5.7.1–5.7.2 插件市场** | `data/plugin/PluginManager`+`ui/plugins`（字幕/资源/美化三类、官方审核标记、checksum 校验、拒绝非官方条目） |
| 5.7.3 云同步 | `data/sync/SyncManager`（配置/收藏/记录打包→SAF/云盘目录，含状态页） |
| 5.7.4 源导入导出/备份恢复 | 设置中心源管理（M3U 网址/文件/Xtream 导入、导出 M3U）+`data/backup/BackupManager`（全量 JSON 备份/恢复） |
| **5.8 纯净度** | 无任何广告 SDK/弹窗/推送；核心功能全免费 |
| 主题系统（下篇三~八章） | `core/theme`（THEME_SYSTEM/LIGHT/DARK、文档全部色值、四级文字、焦点专属色、14sp 下限、皮肤守卫配色禁忌） |
| 内置 10 条高清样片 | `assets/builtin/puretv_demo.m3u` +`data/builtin/BuiltinSourceInstaller`（4 官方直播 + 4 自适应 HLS + 1080P MP4 + 4K WebM，全部实测可用） |
| 内置 10 条 TVBox 聚合影视线路 | `assets/builtin/puretv_tvbox.json`（TVBox 标准格式 sites 配置，飞飞/华哥/量子/无尽/红牛/暴风/豆瓣等 MacCMS 采集接口，全部实测可达）+ `TvBoxConfigImporter` 统一解析安装；设置中心支持粘贴任意 TVBox 配置地址（如「饭太硬」.json）导入 |

技术栈与架构详见上文；构建产物：`app/build/outputs/apk/debug/app-debug.apk`。

