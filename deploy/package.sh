#!/bin/bash
set -e

# ============================================
# 打包发布脚本
# 用法: bash deploy/package.sh          # Release 包
#        bash deploy/package.sh debug   # Debug 包
# ============================================

# 参数解析
BUILD_TYPE="release"
if [ "$1" = "debug" ]; then
    BUILD_TYPE="debug"
fi

IS_DEBUG="false"
[ "$BUILD_TYPE" = "debug" ] && IS_DEBUG="true"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

echo "============================================"
echo "1. Git revert 本地修改 + 拉取最新代码"
echo "============================================"
git checkout -- .
git clean -fd
git pull

echo ""
echo "============================================"
echo "2. 构建 $BUILD_TYPE APK"
echo "============================================"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

if [ "$IS_DEBUG" = "true" ]; then
    echo "🔧 Debug 模式，使用 debug 签名"
    ./gradlew assembleLightningPlusDebug --no-daemon
else
    echo "📦 Release 模式"
    ./gradlew assembleLightningPlusRelease --no-daemon
fi

echo ""
echo "============================================"
echo "3. 重命名 APK"
echo "============================================"
APK_DIR="$PROJECT_DIR/app/build/outputs/apk/lightningPlus/$BUILD_TYPE"
APK_FILE=$(ls "$APK_DIR"/*.apk 2>/dev/null | head -1)

if [ -z "$APK_FILE" ]; then
    echo "❌ 未找到 ${BUILD_TYPE} APK 文件"
    exit 1
fi

# 重命名为 zhaomo_browser.apk（debug 包加后缀）
DIST_NAME="zhaomo_browser.apk"
[ "$IS_DEBUG" = "true" ] && DIST_NAME="zhaomo_browser_debug.apk"
cp "$APK_FILE" "$PROJECT_DIR/deploy/$DIST_NAME"
echo "✅ APK 已复制为: deploy/$DIST_NAME"

echo ""
echo "🎉 打包完成！"

