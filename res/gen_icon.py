#!/usr/bin/env python3
"""
用 res/ 下的源图生成并替换各密度目录下的 ic_launcher 图标文件

流程:
  1. icon_front.png 叠加到 icon_bg.png 上裁剪为圆形生成 icon_circle.png
  2. 将 3 个源图缩放并替换到各 mipmap 密度目录

映射关系:
  res/icon_front.png   -> ic_launcher_foreground.png  (自适应图标前景层, 108dp)
  res/icon_bg.png      -> ic_launcher_background.png  (自适应图标背景层, 108dp)
  res/icon_circle.png  -> ic_launcher.png             (传统图标, 48dp)

用法: conda run -n flask_env python res/gen_icon.py
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

# ---- 配置 ----
PROJECT_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res"

# 源图 -> (输出文件名, 基准密度尺寸 px)
TASKS = [
    ("icon_front.png",  "ic_launcher_foreground.png",  108),
    ("icon_bg.png",     "ic_launcher_background.png",  108),
    ("icon_circle.png", "ic_launcher.png",              48),
]

# 密度缩放倍数
DENSITIES = {
    "mipmap-mdpi":    1.0,
    "mipmap-hdpi":    1.5,
    "mipmap-xhdpi":   2.0,
    "mipmap-xxhdpi":  3.0,
    "mipmap-xxxhdpi": 4.0,
}


def make_circle(img):
    """将方形图片裁剪为圆形（透明背景）"""
    mask = Image.new("L", img.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0) + img.size, fill=255)

    result = img.copy()
    result.putalpha(mask)
    return result


def generate_circle_icon():
    """icon_front.png 叠加到 icon_bg.png 上再裁剪为圆形生成 icon_circle.png"""
    front_path = PROJECT_ROOT / "res" / "icon_front.png"
    bg_path = PROJECT_ROOT / "res" / "icon_bg.png"
    circle_path = PROJECT_ROOT / "res" / "icon_circle.png"

    if not front_path.exists() or not bg_path.exists():
        print(f"  [跳过] 缺少源图")
        return

    front = Image.open(front_path).convert("RGBA")
    bg = Image.open(bg_path).convert("RGBA")

    # 统一到较小尺寸的正方形
    size = min(front.size[0], front.size[1], bg.size[0], bg.size[1])

    def square_crop(img):
        w, h = img.size
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        return img.crop((left, top, left + side, top + side)).resize((size, size), Image.LANCZOS)

    bg_sq = square_crop(bg)
    fg_sq = square_crop(front)

    # 叠加前景到背景
    combined = Image.alpha_composite(bg_sq, fg_sq)
    # 圆形裁剪
    circle = make_circle(combined)
    circle.save(circle_path, "PNG")
    print(f"\n生成: res/icon_circle.png ({circle.size[0]}x{circle.size[1]})")


def process(source_name, out_name, base_size):
    src_path = PROJECT_ROOT / "res" / source_name
    if not src_path.exists():
        print(f"  [跳过] 源文件不存在: {src_path}")
        return

    src_img = Image.open(src_path)
    print(f"\n源图: {source_name} ({src_img.size[0]}x{src_img.size[1]})")

    for density, scale in DENSITIES.items():
        size = int(round(base_size * scale))
        out_path = RES_DIR / density / out_name

        img = src_img.copy()
        w, h = img.size
        min_side = min(w, h)
        left = (w - min_side) // 2
        top = (h - min_side) // 2
        img = img.crop((left, top, left + min_side, top + min_side))
        img = img.resize((size, size), Image.LANCZOS)
        if img.mode != "RGBA":
            img = img.convert("RGBA")
        img.save(out_path, "PNG")
        print(f"  {density}/{out_name}  ({size}x{size})")


def main():
    # 第一步：从 icon_front.png 生成圆形图标
    print("=== 步骤1: 生成 icon_circle.png ===")
    generate_circle_icon()

    # 第二步：替换各密度目录
    print("\n=== 步骤2: 替换各密度图标 ===")
    for source_name, out_name, base_size in TASKS:
        process(source_name, out_name, base_size)

    print("\n完成！所有图标已更新。")


if __name__ == "__main__":
    main()
