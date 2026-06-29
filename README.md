# Mineradio Android

沉浸式音乐播放器 Android 移植版，融合天气电台、歌词舞台、粒子视觉和 3D 歌单架。

基于原 [Mineradio](https://github.com/XxHuberrr/Mineradio) (Electron 桌面版) 移植。

## 技术架构

```
WebView (Three.js + GSAP 前端) ← JS Bridge → Kotlin 原生层
                                                ├── Ktor HTTP Server (本地 API)
                                                ├── ExoPlayer (音频播放)
                                                ├── Room/SharedPrefs (数据持久化)
                                                └── MediaSession (后台播放 + 通知)
```

## 在 Android Studio 中打开

1. 启动 Android Studio
2. 选择 **File → Open**
3. 选择本项目根目录 `mineradio-android/`
4. 等待 Gradle 同步完成
5. 连接 Android 设备或启动模拟器（API 26+）
6. 点击 **Run** 按钮

## 项目结构

```
app/src/main/java/com/mineradio/app/
├── MainActivity.kt              # 主活动：WebView + 全屏沉浸
├── MediaPlaybackService.kt      # 后台播放 Service
├── MineradioApp.kt              # Application 类
├── bridge/
│   └── MineradioJSBridge.kt     # JS ↔ Native 桥接 (~30个方法)
├── server/
│   ├── MineradioServer.kt       # Ktor 服务器 (~30个API路由)
│   ├── netease/
│   │   ├── NeteaseApi.kt        # 网易云音乐 API
│   │   └── NeteaseCrypto.kt     # weapi/eapi 加密
│   ├── qq/
│   │   └── QQMusicApi.kt        # QQ音乐 API
│   ├── weather/
│   │   └── WeatherRadio.kt      # 天气电台 (Open-Meteo)
│   ├── proxy/
│   │   └── AudioCoverProxy.kt   # 音频/封面代理
│   └── podcast/
│       └── DjAnalyzer.kt        # 节拍分析 (移植自 dj-analyzer.js)
├── player/
│   └── AudioPlayerManager.kt    # ExoPlayer 管理
├── stats/
│   ├── ListenRecord.kt          # 听歌记录数据模型
│   └── StatsRepository.kt       # 听歌统计仓库
└── util/
    ├── CookieManager.kt         # Cookie 持久化
    └── PreferencesHelper.kt     # SharedPreferences 封装
```

## 已实现功能

- ✅ 网易云音乐：搜索、歌曲URL、歌词、登录（Cookie/QR）、歌单、收藏、评论、艺术家
- ✅ QQ音乐：搜索、歌曲URL、歌词、登录
- ✅ 天气电台：Open-Meteo 天气 + IP 定位 + 心情推荐
- ✅ 音频代理 + 封面代理（绕过 CORS）
- ✅ Three.js 粒子视觉（WebView + WebGL）
- ✅ GSAP 动画
- ✅ 音频节拍分析（DjAnalyzer - Biquad 滤波 + Tempo 估计）
- ✅ **听歌时间统计**（新功能！每日/每周/时段分布/连续天数）
- ✅ 后台播放 + 通知栏媒体控制
- ✅ 横竖屏自适应
- ✅ 沉浸式全屏
- ✅ 本地 Ktor HTTP 服务器 (~49个API端点)

## 构建配置

- **最低 API**: 26 (Android 8.0)
- **目标 API**: 36
- **编译 SDK**: 36
- **语言**: Kotlin 2.1.0
- **Gradle**: 8.11.1

## 注意事项

1. 首次打开 Android Studio 时需要下载依赖，请确保网络畅通
2. 若构建报错缺少 SDK Platform 36，请在 SDK Manager 中安装
3. 音频播放需要有效的网络连接
4. 登录功能需要通过浏览器登录后粘贴 Cookie
5. 本项目遵循 GPL-3.0 协议
