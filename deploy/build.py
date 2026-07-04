#!/usr/bin/env python3
"""
Lightning Browser - 跨平台构建脚本
支持 Windows, macOS, Linux
"""

import os
import sys
import platform
import subprocess
from pathlib import Path


class BuildConfig:
    """构建配置"""
    
    def __init__(self):
        self.system = platform.system()
        self.project_root = Path(__file__).parent.parent
        self.gradle_wrapper = self.project_root / "gradlew"
        
        # SDK 路径配置
        if self.system == "Windows":
            self.sdk_root = Path("C:/SDK")
            self.gradle_exe = str(self.project_root / "gradlew.bat")
        else:
            self.sdk_root = Path.home() / "SDK"
            self.gradle_exe = str(self.project_root / "gradlew")
        
        self.java_home = self.sdk_root / "jdk-21"
        self.android_home = self.sdk_root / "android-sdk"
        self.gradle_user_home = self.sdk_root / "gradle-cache"
        
    def setup_env(self):
        """设置环境变量"""
        env = os.environ.copy()
        env['JAVA_HOME'] = str(self.java_home)
        env['ANDROID_HOME'] = str(self.android_home)
        env['GRADLE_USER_HOME'] = str(self.gradle_user_home)  # Set Gradle cache directory
        
        # 添加 PATH
        if self.system == "Windows":
            path_sep = ";"
            bin_dir = "bin"
        else:
            path_sep = ":"
            bin_dir = "bin"
        
        current_path = env.get('PATH', '')
        new_paths = [
            str(self.java_home / bin_dir),
            str(self.android_home / "cmdline-tools" / "latest" / bin_dir),
            str(self.android_home / "platform-tools")
        ]
        env['PATH'] = path_sep.join(new_paths) + path_sep + current_path
        
        return env
    
    def run_gradle(self, *args, use_cache=True):
        """运行 Gradle 命令"""
        cmd = [self.gradle_exe] + list(args)
        
        if not use_cache:
            cmd.append("--no-configuration-cache")
        
        print(f"\n{'='*60}")
        print(f"执行命令: {' '.join(cmd)}")
        print(f"{'='*60}\n")
        
        env = self.setup_env()
        
        try:
            result = subprocess.run(
                cmd,
                cwd=self.project_root,
                env=env,
                check=True
            )
            return result.returncode == 0
        except subprocess.CalledProcessError as e:
            print(f"\n❌ 构建失败: {e}")
            return False
        except FileNotFoundError:
            print(f"\n❌ 找不到 Gradle Wrapper，请确保在项目根目录")
            return False


def build_debug(config):
    """构建 Debug 版本"""
    print("\n🔨 开始构建 Debug APK...")
    return config.run_gradle("clean", "assembleLightningPlusDebug")


def build_release(config):
    """构建 Release 版本"""
    print("\n🔨 开始构建 Release APK...")
    return config.run_gradle("clean", "assembleLightningPlusRelease")


def install_apk(config):
    """安装 APK 到设备"""
    print("\n📱 安装 APK 到设备...")
    
    apk_path = config.project_root / "app" / "build" / "outputs" / "apk" / "lightningPlus" / "debug" / "app-lightningPlus-debug.apk"
    
    if not apk_path.exists():
        print(f"❌ APK 文件不存在: {apk_path}")
        return False
    
    env = config.setup_env()
    adb_cmd = "adb.exe" if config.system == "Windows" else "adb"
    
    try:
        subprocess.run([adb_cmd, "install", "-r", str(apk_path)], check=True)
        print("✅ APK 安装成功！")
        return True
    except subprocess.CalledProcessError:
        print("❌ APK 安装失败，请确保设备已连接")
        return False
    except FileNotFoundError:
        print("❌ 找不到 ADB，请确保 Android SDK 已正确安装")
        return False


def clean_build(config):
    """清理构建产物"""
    print("\n🧹 清理构建产物...")
    return config.run_gradle("clean")


def show_help():
    """显示帮助信息"""
    help_text = """
╔═══════════════════════════════════════════════════════════╗
║           Lightning Browser 构建工具                      ║
╚═══════════════════════════════════════════════════════════╝

用法: python build.py [命令]

命令:
  build       构建 Debug APK（默认）
  release     构建 Release APK
  install     安装 APK 到设备
  clean       清理构建产物
  all         完整流程：清理 -> 构建 -> 安装
  help        显示此帮助信息

示例:
  python build.py build      # 构建 Debug 版本
  python build.py install    # 安装到设备
  python build.py all        # 完整流程

环境要求:
  - JDK 21: {java_home}
  - Android SDK: {android_home}
""".format(
        java_home="C:\\SDK\\jdk-21" if platform.system() == "Windows" else "~/SDK/jdk-21",
        android_home="C:\\SDK\\android-sdk" if platform.system() == "Windows" else "~/SDK/android-sdk"
    )
    print(help_text)


def main():
    """主函数"""
    config = BuildConfig()
    
    # 检查参数
    command = sys.argv[1] if len(sys.argv) > 1 else "build"
    
    commands = {
        "build": lambda: build_debug(config),
        "release": lambda: build_release(config),
        "install": lambda: install_apk(config),
        "clean": lambda: clean_build(config),
        "all": lambda: (
            clean_build(config) and 
            build_debug(config) and 
            install_apk(config)
        ),
        "help": show_help
    }
    
    if command in commands:
        if command == "help":
            commands[command]()
        else:
            success = commands[command]()
            sys.exit(0 if success else 1)
    else:
        print(f"❌ 未知命令: {command}")
        show_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
