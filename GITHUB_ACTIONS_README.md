# GitHub Actions 自动构建 APK 使用指南

## 🚀 快速开始

### 1. 推送代码到 GitHub
```bash
cd /sdcard/Download/DSH-Go/DeltakForceSource
git init
git add .
git commit -m "Initial commit: DeltaForce Assistant"
git branch -M main
git remote add origin https://github.com/你的用户名/DeltaForceAssistant.git
git push -u origin main
```

### 2. 自动触发构建
推送到 `main` 分支后，GitHub Actions 会自动：
- ✅ 检出代码
- ✅ 配置 Java 17 + Android SDK (API 34) + NDK r26d
- ✅ 缓存 Gradle 依赖（加速后续构建）
- ✅ 执行 `./gradlew :app:assembleDebug`
- ✅ 上传 `app-debug.apk` 为 Artifact

### 3. 下载构建产物
- 进入 GitHub 仓库 → **Actions** 标签
- 点击最新的 workflow 运行
- 在 **Artifacts** 区域下载 `DeltaForceAssistant-debug.zip`
- 解压得到 `app-debug.apk`

---

## 📱 安装到手机
```bash
# 方式 1：ADB 安装
adb install app-debug.apk

# 方式 2：传到手机点击安装
# 需要开启"允许安装未知来源应用"
```

---

## 🔐 可选：配置 Release 签名（正式发布用）

在 GitHub 仓库 **Settings → Secrets and variables → Actions** 添加：

| Secret 名称 | 说明 |
|-------------|------|
| `KEYSTORE_BASE64` | `base64 -w0 your.keystore` 输出的字符串 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |
| `STORE_PASSWORD` | keystore 密码 |

配置后，推送 tag 会自动构建签名 Release APK：
```bash
git tag v1.0.0
git push origin v1.0.0
```

---

## ⚙️ 自定义构建

### 修改包名 / 版本
编辑 `app/build.gradle.kts`：
```kotlin
defaultConfig {
    applicationId = "com.yourname.deltaforce"
    versionCode = 2
    versionName = "1.1"
}
```

### 添加混淆规则
编辑 `app/proguard-rules.pro`：
```proguard
-keep class com.deltaforce.assistant.** { *; }
-dontwarn com.deltaforce.assistant.**
```

### 启用 ARM64 专用构建（实验性）
在 `.github/workflows/build.yml` 中将 `build-arm64` job 的 `if: false` 改为 `if: true`

---

## 🐛 常见问题

| 问题 | 解决方法 |
|------|----------|
| 构建超时 | 在 workflow 添加 `timeout-minutes: 60` |
| 依赖下载失败 | 网络问题，重新运行 workflow |
| NDK 版本不匹配 | 修改 `ndk-version: r26d` 为本地版本 |
| 签名失败 | 检查 Secrets 是否正确配置 |

---

## 📂 本项目结构
```
DeltakForceSource/
├── .github/workflows/build.yml   # GitHub Actions 配置
├── app/                          # 主模块
│   ├── src/main/
│   │   ├── java/com/deltaforce/assistant/
│   │   │   ├── features/    # Aimbot/ESP/Radar
│   │   │   ├── game/        # GameContext/UE4Parser/GameLoop
│   │   │   ├── memory/      # MemoryReader (JNI)
│   │   │   ├── render/      # ESPRenderer (OpenGL ES)
│   │   │   ├── input/       # InputInjector (uinput)
│   │   │   ├── ui/          # FloatingService (悬浮窗)
│   │   │   └── utils/       # RootUtil
│   │   ├── res/             # 资源文件
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── cpp/                       # C++ 原生代码
│   ├── native-lib.cpp         # 内存读写
│   ├── ue4parser.cpp          # UE4 解析
│   ├── input_injector.cpp     # uinput 注入
│   └── CMakeLists.txt
├── gradle/
├── gradlew
├── settings.gradle.kts
└── build.gradle.kts
```

---

## 🔗 相关链接
- [GitHub Actions 文档](https://docs.github.com/actions)
- [Android Gradle Plugin 文档](https://developer.android.com/studio/build)
- [NDK 文档](https://developer.android.com/ndk)