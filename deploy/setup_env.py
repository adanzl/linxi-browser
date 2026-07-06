#!/usr/bin/env python3
"""
 环境设置脚本
自动配置开发环境
"""

import os
import re
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
        elif self.system == "Linux":
            self.sdk_root = Path("/opt")
        else:
            # macOS (Darwin)
            self.sdk_root = Path.home() / "SDK"
        
        self.java_home = self.sdk_root / "jdk-21"
        self.android_home = self.sdk_root / "android-sdk"
        if self.system == "Windows":
            self.gradle_user_home = self.sdk_root / "gradle-cache"
        else:
            self.gradle_user_home = Path.home() / ".gradle"
        
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
            print()  # 进度条换行
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
        self.java_home.mkdir(parents=True, exist_ok=True)
        jdk_folders = list(temp_dir.glob("jdk-*"))
        if jdk_folders:
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
        if not self.install_sdk_components():
            return False
        
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

        # 自动接受许可协议
        print("  接受 SDK 许可协议...")
        try:
            subprocess.run(
                [str(sdkmanager), "--licenses"],
                input=b"y\n" * 10,
                env=env,
                cwd=self.android_home,
                capture_output=True
            )
            print("  ✅ 许可协议已接受")
        except subprocess.CalledProcessError:
            print("  ⚠️  接受许可协议出现警告，继续安装...")

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
                # 尝试获取更多错误信息
                if e.stderr:
                    print(f"     错误详情: {e.stderr.decode('utf-8', errors='replace')[:500]}")
                return False

        return True
    
    def setup_gradle_cache(self):
        """设置 Gradle 缓存目录"""
        print("\n⚙️  设置 Gradle 缓存...")
        
        self.gradle_user_home.mkdir(parents=True, exist_ok=True)
        
        print(f"✅ Gradle 缓存目录: {self.gradle_user_home}")
        return True

    def _setup_shell_env(self):
        """自动将环境变量写入 shell 配置文件并 source 生效"""
        print("\n" + "-"*60)
        print("📌 配置环境变量...")
        print("-"*60)

        if self.system == "Windows":
            print(f"    $env:JAVA_HOME = \"{self.java_home}\"")
            print(f"    $env:ANDROID_HOME = \"{self.android_home}\"")
            print(f"    $env:GRADLE_USER_HOME = \"{self.gradle_user_home}\"")
            print(f"    $env:Path += \";{self.java_home / 'bin'}\"")
            print(f"    $env:Path += \";{self.android_home / 'platform-tools'}\"")
            print("\n    ⚠️  Windows 请手动添加系统环境变量")
            return True

        # 确定 shell 配置文件
        shell = os.environ.get("SHELL", "")
        if "zsh" in shell:
            rc_file = Path.home() / ".zshrc"
            shell_name = "zsh"
        elif "bash" in shell:
            rc_file = Path.home() / ".bashrc"
            shell_name = "bash"
        elif self.system == "Darwin":
            rc_file = Path.home() / ".zshrc"
            shell_name = "zsh"
        else:
            rc_file = Path.home() / ".bashrc"
            shell_name = "bash"

        print(f"  检测到 shell: {shell_name}, 配置文件: {rc_file.name}")

        # 构造环境变量块
        block_start = "# === Linxi Browser Env ==="
        block_end = "# === End Linxi Browser Env ==="

        java_home_path = str(self.java_home)
        android_home_path = str(self.android_home)
        gradle_home_path = str(self.gradle_user_home)
        java_bin = str(self.java_home / "bin")
        platform_tools = str(self.android_home / "platform-tools")

        def _quote_path(p):
            return f"\"{p}\"" if " " in p else p

        env_vars = [
            f"export JAVA_HOME={_quote_path(java_home_path)}",
            f"export ANDROID_HOME={_quote_path(android_home_path)}",
            f"export GRADLE_USER_HOME={_quote_path(gradle_home_path)}",
            f"export PATH=$PATH:{_quote_path(java_bin)}:{_quote_path(platform_tools)}",
        ]

        env_block = (
            f"{block_start}\n"
            + "\n".join(env_vars) + "\n"
            + f"{block_end}\n"
        )

        try:
            if rc_file.exists():
                content = rc_file.read_text(encoding="utf-8", errors="replace")
                if block_start in content:
                    # 替换已有块
                    content = re.sub(
                        re.escape(block_start) + ".*?" + re.escape(block_end),
                        env_block.strip(),
                        content,
                        flags=re.DOTALL
                    )
                    action = "更新"
                else:
                    # 追加到末尾
                    content = content.rstrip() + "\n\n" + env_block
                    action = "添加"
            else:
                content = env_block
                action = "创建"

            rc_file.write_text(content, encoding="utf-8")
            print(f"  ✅ 环境变量已{action}到 ~/{rc_file.name}")

            # 打印各变量值以供确认
            for var_line in env_vars:
                print(f"    {var_line}")

            # source 使其在当前终端立即生效
            try:
                subprocess.run(
                    [shell_name, "-c", f"source {rc_file}"],
                    capture_output=True, timeout=5
                )
                print(f"  ✅ 已 source，若当前终端未生效请手动执行: source ~/{rc_file.name}")
            except Exception as e:
                print(f"  ⚠️  自动 source 失败: {e}")
                print(f"     请手动执行: source ~/{rc_file.name}")

        except Exception as e:
            print(f"  ❌ 写入环境变量失败: {e}")
            print(f"     请手动将以下内容添加到 ~/{rc_file.name}:")
            for var_line in env_vars:
                print(f"    {var_line}")

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
        print(f"Gradle 缓存: {self.gradle_user_home}")
        
        # 自动配置环境变量
        self._setup_shell_env()
        
        print(f"\n现在可以运行: python deploy/build.py build")
        
        return True


def main():
    """主函数"""
    setup = EnvSetup()
    success = setup.run()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
