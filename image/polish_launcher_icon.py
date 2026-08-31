#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
启动器图标「琥珀守护」视觉增强。

目标（用户反馈）：手机上金色不够显眼、偏淡，缺乏光泽和渐变，整体呆板。

做法：不重画造型，只在现有成品图上做增强，保住「琥珀守护」的设计：
  1. foreground（琥珀金棱堡爪印）：
     - 饱和度 / 对比度提升，让金色更"跳"；
     - 竖向渐变（上亮金 → 下深琥珀），制造金属受光感；
     - 顶部椭圆高光（柔化后 screen 混合），制造光泽；
     - alpha 通道全程保持不变（安全区与系统遮罩不受影响）。
  2. background（深色玻璃底）：
     - 竖向渐变 + 中心柔和光晕，去掉"死板纯色"感；
     - 保持深底，确保前景金色对比度（记忆：深底 6.8:1，白玻璃仅 2.3:1）。
  3. monochrome 不动（主题图标用剪影，着色由系统接管）。

产出：以 xxxhdpi(432) 为主图增强后，LANCZOS 降采样到各密度写回。
原始文件先备份到 image/icon_backup_original/，可随时还原。
"""
import os
import shutil
from datetime import datetime

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter

RES = r"D:\Bastion\bastion\Bastion\app\src\main\res"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BACKUP_DIR = os.path.join(SCRIPT_DIR, "icon_backup_original")

DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
MASTER = "xxxhdpi"


def path_of(kind: str, density: str) -> str:
    return os.path.join(RES, f"drawable-{density}", f"ic_launcher_{kind}.png")


def backup(kind: str) -> None:
    os.makedirs(BACKUP_DIR, exist_ok=True)
    for density in DENSITIES:
        src = path_of(kind, density)
        if os.path.exists(src):
            dst = os.path.join(BACKUP_DIR, f"ic_launcher_{kind}_{density}.png")
            if not os.path.exists(dst):
                shutil.copy2(src, dst)


def vertical_gradient(size, top_rgb, bottom_rgb) -> Image.Image:
    w, h = size
    strip = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(h - 1, 1)
        strip.putpixel(
            (0, y),
            tuple(int(top_rgb[i] + (bottom_rgb[i] - top_rgb[i]) * t) for i in range(3)),
        )
    return strip.resize((w, h), Image.BILINEAR)


def radial_glow(size, box, fill: int, blur_ratio: float) -> Image.Image:
    w, h = size
    layer = Image.new("L", size, 0)
    ImageDraw.Draw(layer).ellipse(
        [box[0] * w, box[1] * h, box[2] * w, box[3] * h], fill=fill
    )
    layer = layer.filter(ImageFilter.GaussianBlur(w * blur_ratio))
    return Image.merge("RGB", (layer, layer, layer))


def enhance_foreground(img: Image.Image) -> Image.Image:
    size = img.size
    w, h = size
    alpha = img.getchannel("A")

    rgb = img.convert("RGB")
    rgb = ImageEnhance.Color(rgb).enhance(1.45)      # 提饱和，金色更"跳"
    rgb = ImageEnhance.Contrast(rgb).enhance(1.12)   # 拉开明暗，去掉灰扑扑
    rgb = ImageEnhance.Brightness(rgb).enhance(1.05)

    # 竖向渐变：上亮金 → 下深琥珀，制造金属受光
    grad = vertical_gradient(size, (255, 224, 156), (176, 106, 16))
    overlayed = ImageChops.overlay(rgb, grad)
    rgb = Image.blend(rgb, overlayed, 0.5)

    # 顶部高光：椭圆柔化后 screen 混合，制造光泽
    gloss = radial_glow(size, (0.10, -0.16, 0.90, 0.44), fill=72, blur_ratio=0.07)
    rgb = Image.blend(rgb, ImageChops.screen(rgb, gloss), 0.6)

    # 底部轻微压暗，增强立体感（避免整枚图标"平"）
    shade = radial_glow(size, (0.05, 0.62, 0.95, 1.25), fill=46, blur_ratio=0.14)
    rgb = ImageChops.subtract(rgb, shade)

    out = rgb.convert("RGBA")
    out.putalpha(alpha)  # alpha 完全保留：安全区与系统遮罩不变
    return out


def enhance_background(img: Image.Image) -> Image.Image:
    size = img.size
    w, h = size
    base = img.convert("RGB")

    # 竖向渐变：上暖褐 → 下近黑，去掉死板纯色
    grad = vertical_gradient(size, (52, 38, 24), (14, 10, 7))
    out = Image.blend(base, ImageChops.overlay(base, grad), 0.55)

    # 中心柔和光晕，让爪印后面有"透光"层次
    glow = radial_glow(size, (0.12, 0.10, 0.88, 0.86), fill=58, blur_ratio=0.20)
    out = ImageChops.screen(out, glow)

    if img.mode == "RGBA":
        out = out.convert("RGBA")
        out.putalpha(img.getchannel("A"))
    return out


def gold_stats(img: Image.Image) -> str:
    """统计非透明像素的平均色与饱和度，用于前后对比。"""
    import colorsys

    px = img.convert("RGBA").load()
    w, h = img.size
    n = 0
    rs = gs = bs = 0
    sat_sum = 0.0
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            r, g, b, a = px[x, y]
            if a < 40:
                continue
            n += 1
            rs += r
            gs += g
            bs += b
            sat_sum += colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)[1]
    if n == 0:
        return "no opaque pixels"
    return (
        f"mean=({rs // n},{gs // n},{bs // n}) "
        f"meanSat={sat_sum / n:.3f} samples={n}"
    )


def main() -> None:
    master_size = DENSITIES[MASTER]
    results = {}

    for kind, fn in (("foreground", enhance_foreground), ("background", enhance_background)):
        backup(kind)
        src = path_of(kind, MASTER)
        original = Image.open(src).convert("RGBA")
        if original.size[0] != master_size:
            print(f"[warn] {kind} master is {original.size}, expected {master_size}")
        before = gold_stats(original)
        enhanced = fn(original)
        after = gold_stats(enhanced)

        for density, size in DENSITIES.items():
            out = enhanced if density == MASTER else enhanced.resize(
                (size, size), Image.LANCZOS
            )
            out.save(path_of(kind, density), "PNG", optimize=True)

        results[kind] = (before, after)
        print(f"[{kind}] before: {before}")
        print(f"[{kind}] after : {after}")

    print("\nmaster size:", master_size)
    print("backup dir :", BACKUP_DIR)
    print("done at    :", datetime.now().strftime("%Y-%m-%d %H:%M:%S"))


if __name__ == "__main__":
    main()
