# Lightning Browser 部署工具

## 📦 快速开始

### 1. 环境设置（首次使用）

```bash
# Windows
python deploy/setup_env.py

# macOS/Linux  
python3 deploy/setup_env.py
```

这将自动安装：
- JDK 21
- Android SDK
- 必需的 SDK 组件
- Gradle 缓存配置

### 2. 构建项目

```bash
# 构建 Debug APK
python deploy/build.py build

# 构建 Release APK
python deploy/build.py release

# 安装到设备
python deploy/build.py install

# 完整流程（清理 + 构建 + 安装）
python deploy/build.py all
```

### 3. 其他命令

```bash
# 清理构建产物
python deploy/build.py clean

# 查看帮助
python deploy/build.py help
```

## 📁 目录结构

```
deploy/
├── build.py          # 主构建脚本
├── setup_env.py      # 环境设置脚本
└── README.md         # 本文档
```

## ⚙️ 配置

### SDK 路径（自动检测）

构建脚本会自动根据操作系统设置路径：

**Windows:** `C:\SDK`
- JDK: `C:\SDK\jdk-21`
- Android SDK: `C:\SDK\android-sdk`
- Gradle Cache: `C:\SDK\gradle-cache`

**macOS/Linux:** `~/SDK`
- JDK: `~/SDK/jdk-21`
- Android SDK: `~/SDK/android-sdk`
- Gradle Cache: `~/SDK/gradle-cache`

### 环境变量

Python 脚本会自动设置以下环境变量：
- `JAVA_HOME` - JDK 安装路径
- `ANDROID_HOME` - Android SDK 路径
- `GRADLE_USER_HOME` - Gradle 缓存目录（包含 caches、wrapper、notifications）

### 修改 SDK 路径

如需自定义 SDK 路径，编辑 `deploy/build.py` 和 `deploy/setup_env.py` 中的 `sdk_root` 变量。

## 🔧 故障排除

### 问题：找不到 Java

确保 JDK 已正确安装：
```bash
java -version
```

### 问题：Gradle 下载失败

检查网络连接，或手动下载 Gradle 到缓存目录。

### 问题：ADB 找不到设备

1. 启用设备的开发者选项和 USB 调试
2. 运行 `adb devices` 检查连接
3. 安装设备驱动程序（Windows）

## 📝 注意事项

- 首次构建会下载大量依赖，请确保网络畅通
- 建议至少有 5GB 可用磁盘空间
- Mezzanine 插件当前已禁用（KSP2 兼容性问题）

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！
