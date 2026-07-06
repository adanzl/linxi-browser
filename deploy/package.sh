#!/bin/bash
set -e

# ============================================
# Linxi Browser - 打包发布脚本
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
echo "============================================"
echo "4. 复制到目标目录（保留历史版本，最多5个）"
echo "============================================"
DIST_DIR="/mnt/data/project/MyTodo/dist/apk"
mkdir -p "$DIST_DIR"

TARGET_FILE="$DIST_DIR/$DIST_NAME"

# 如果目标已存在同名 APK，重命名旧文件
if [ -f "$TARGET_FILE" ]; then
    TIMESTAMP=$(stat -c "%Y" "$TARGET_FILE" 2>/dev/null || date +%s)
    OLD_NAME="zhaomo_browser_$(date -d @"$TIMESTAMP" '+%Y%m%d_%H%M%S').apk"
    mv "$TARGET_FILE" "$DIST_DIR/$OLD_NAME"
    echo "📦 旧版重命名: $OLD_NAME"
fi

# 复制新 APK 到目标目录
cp "$PROJECT_DIR/deploy/$DIST_NAME" "$TARGET_FILE"
echo "✅ 已复制到: $TARGET_FILE"

# 清理：只保留最近5个，按文件修改时间排序
echo ""
echo "📋 清理旧版本（保留最近5个）..."
cd "$DIST_DIR"
# ls -t 按修改时间排序（最新的在前），排除当前 zhaomo_browser.apk
ARCHIVES=$(ls -t zhaomo_browser_*.apk 2>/dev/null | tail -n +6)
if [ -n "$ARCHIVES" ]; then
    echo "$ARCHIVES" | while read -r OLD_FILE; do
        rm -f "$DIST_DIR/$OLD_FILE"
        echo "🗑️  已删除: $OLD_FILE"
    done
else
    echo "✅ 无需清理"
fi

# 显示最终目录列表
echo ""
echo "📂 $DIST_DIR 文件列表:"
ls -lh "$DIST_DIR/" 2>/dev/null || echo "(空)"

echo ""
echo "🎉 打包完成！"

