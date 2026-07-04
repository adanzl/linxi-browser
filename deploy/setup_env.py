#!/usr/bin/env python3
"""
Lightning Browser - 环境设置脚本
自动配置开发环境
"""

import os
import sys
import platform
import subprocess
from pathlib import Path
from urllib.request import urlretrieve
import zipfile
import shutil


class EnvSetup:
    """环境设置"""
    
    def __init__(self):
        self.system = platform.system()
        
        if self.system == "Windows":
            self.sdk_root = Path("C:/SDK")
        else:
            self.sdk_root = Path.home() / "SDK"
        
        self.java_home = self.sdk_root / "jdk-21"
        self.android_home = self.sdk_root / "android-sdk"
        self.gradle_user_home = self.sdk_root / "gradle-cache"
        
    def check_prerequisites(self):
        """检查前置条件"""
        print("\n🔍 检查环境...")
        
        # 检查 Python
        try:
            python_version = platform.python_version()
            print(f"✅ Python {python_version}")
        except Exception as e:
            print(f"❌ Python 检查失败: {e}")
            return False
        
        # 检查 SDK 目录
        if not self.sdk_root.exists():
            print(f"📁 创建 SDK 目录: {self.sdk_root}")
            self.sdk_root.mkdir(parents=True, exist_ok=True)
        
        return True
    
    def download_file(self, url, dest):
        """下载文件"""
        print(f"⬇️  下载: {url}")
        print(f"   保存到: {dest}")
        
        try:
            urlretrieve(url, dest, reporthook=self.download_progress)
            print("✅ 下载完成")
            return True
        except Exception as e:
            print(f"❌ 下载失败: {e}")
            return False
    
    def download_progress(self, block_num, block_size, total_size):
        """显示下载进度"""
        downloaded = block_num * block_size
        if total_size > 0:
            percent = min(100, downloaded * 100 / total_size)
            mb_downloaded = downloaded / (1024 * 1024)
            mb_total = total_size / (1024 * 1024)
            print(f"\r   进度: {percent:.1f}% ({mb_downloaded:.1f}/{mb_total:.1f} MB)", end="")
    
    def extract_zip(self, zip_path, dest_dir):
        """解压 ZIP 文件"""
        print(f"📦 解压: {zip_path}")
        print(f"   到: {dest_dir}")
        
        try:
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                zip_ref.extractall(dest_dir)
            print("✅ 解压完成")
            return True
        except Exception as e:
            print(f"❌ 解压失败: {e}")
            return False
    
    def setup_java(self):
        """安装 JDK 21"""
        print("\n☕ 设置 JDK 21...")
        
        if self.java_home.exists():
            print("✅ JDK 已安装")
            return True
        
        print("📥 下载 JDK 21...")
        jdk_url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_windows_hotspot_21.0.9_10.zip"
        jdk_zip = self.sdk_root / "jdk-21.zip"
        
        if not self.download_file(jdk_url, jdk_zip):
            return False
        
        print("📦 解压 JDK...")
        temp_dir = self.sdk_root / "temp-jdk"
        if not self.extract_zip(jdk_zip, temp_dir):
            return False
        
        # 移动文件
        jdk_folders = list(temp_dir.glob("jdk-*"))
        if jdk_folders:
            self.java_home.mkdir(parents=True, exist_ok=True)
            for item in jdk_folders[0].iterdir():
                shutil.move(str(item), str(self.java_home / item.name))
            shutil.rmtree(temp_dir, ignore_errors=True)
        
        # 清理 zip
        if jdk_zip.exists():
            jdk_zip.unlink()
        
        print("✅ JDK 安装完成")
        return True
    
    def setup_android_sdk(self):
        """安装 Android SDK"""
        print("\n🤖 设置 Android SDK...")
        
        if (self.android_home / "platform-tools").exists():
            print("✅ Android SDK 已安装")
            return True
        
        print("📥 下载 Android Command Line Tools...")
        cmdline_url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
        cmdline_zip = self.sdk_root / "android-cmdline-tools.zip"
        
        if not self.download_file(cmdline_url, cmdline_zip):
            return False
        
        print("📦 解压 Command Line Tools...")
        cmdline_dir = self.android_home / "cmdline-tools"
        cmdline_dir.mkdir(parents=True, exist_ok=True)
        
        if not self.extract_zip(cmdline_zip, cmdline_dir):
            return False
        
        # 重命名为 latest
        extracted = cmdline_dir / "cmdline-tools"
        if extracted.exists():
            shutil.move(str(extracted), str(cmdline_dir / "latest"))
        
        # 清理 zip
        if cmdline_zip.exists():
            cmdline_zip.unlink()
        
        # 安装 SDK 组件
        print("\n📦 安装 SDK 组件...")
        self.install_sdk_components()
        
        print("✅ Android SDK 安装完成")
        return True
    
    def install_sdk_components(self):
        """安装必需的 SDK 组件"""
        sdkmanager = self.android_home / "cmdline-tools" / "latest" / "bin" / ("sdkmanager.bat" if self.system == "Windows" else "sdkmanager")
        
        if not sdkmanager.exists():
            print("❌ 找不到 sdkmanager")
            return False
        
        env = os.environ.copy()
        env['JAVA_HOME'] = str(self.java_home)
        
        components = [
            "platform-tools",
            "platforms;android-36",
            "build-tools;34.0.0"
        ]
        
        for component in components:
            print(f"  安装: {component}")
            try:
                subprocess.run(
                    [str(sdkmanager), component],
                    env=env,
                    cwd=self.android_home,
                    check=True,
                    capture_output=True
                )
                print(f"  ✅ {component}")
            except subprocess.CalledProcessError as e:
                print(f"  ❌ {component} 失败: {e}")
                return False
        
        return True
    
    def setup_gradle_cache(self):
        """设置 Gradle 缓存目录"""
        print("\n⚙️  设置 Gradle 缓存...")
        
        gradle_cache = self.sdk_root / "gradle-cache"
        gradle_cache.mkdir(parents=True, exist_ok=True)
        
        print(f"✅ Gradle 缓存目录: {gradle_cache}")
        return True
    
    def run(self):
        """执行完整设置"""
        print("="*60)
        print("  Lightning Browser - 环境设置")
        print("="*60)
        
        if not self.check_prerequisites():
            return False
        
        if not self.setup_java():
            return False
        
        if not self.setup_android_sdk():
            return False
        
        if not self.setup_gradle_cache():
            return False
        
        print("\n" + "="*60)
        print("  ✅ 环境设置完成！")
        print("="*60)
        print(f"\nSDK 位置: {self.sdk_root}")
        print(f"JDK: {self.java_home}")
        print(f"Android SDK: {self.android_home}")
        print(f"\n现在可以运行: python deploy/build.py build")
        
        return True


def main():
    """主函数"""
    setup = EnvSetup()
    success = setup.run()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
