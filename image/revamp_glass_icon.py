#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
启动器图标重设计：透明玻璃底 + 极夜蓝猫爪（方案 A）。

背景（本次要解决的问题）
------------------------
1. 上一版「灰白玻璃底 + 金黄爪印」实测对比度仅 1.2~1.4:1（爪子与紧邻玻璃区），
   接近不可见，观感"不显眼、不大气"。根因是金黄 #E7BF56 与灰白底亮度过于接近。
2. 「透明玻璃」与「深色爪在深色桌面可见」存在矛盾：玻璃底 alpha 越低，
   深色桌面的暗色越会透上来，深色爪子越会沉进背景。实测：
       alpha=40  → 浅色桌面 6.32:1，深色桌面 1.42:1（爪子消失）
       alpha=232 → 浅色桌面 5.90:1，深色桌面 4.55:1（双端稳定）
   因此本方案取 alpha=232：通透感交给「竖向渐变 + 斜向高光 + 顶部亮描边 +
   底部内阴影」这套光影来表达，而非靠降低不透明度。这也是微信/QQ 图标的实际做法
   ——它们的图标底色基本是实色的，玻璃感来自光影。

做法（不重画造型，沿用现有猫爪轮廓，只做缩放与重着色）
------------------------------------------------------
1. foreground：沿用现有爪印 alpha 轮廓，等比缩放到 74%（原占 55%×62%，
   缩放后约 41%×46%，落在 Android 自适应图标 66dp 安全圈内，不会被系统遮罩切边），
   改用极夜蓝竖向渐变 + 顶部柔光。
2. background：玻璃质感底（渐变 / 斜向高光 / 顶亮描边 / 底内阴影）。
3. monochrome：白色 + alpha 纯剪影（Android 主题图标按此层由系统着色），
   与 foreground 同形状同缩放，保证主题图标与常规图标形状一致。

产出：以 xxxhdpi(432) 为主图，按「预乘 alpha」降采样到各密度写回，
避免透明图层边缘发黑。原始文件先备份到 image/icon_backup_glass_<时间戳>/。
"""
import os
import shutil
from datetime import datetime

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(SCRIPT_DIR), "Bastion", "app", "src", "main", "res")
BACKUP_DIR = os.path.join(SCRIPT_DIR, f"icon_backup_glass_{datetime.now():%Y%m%d_%H%M%S}")

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
MASTER = "xxxhdpi"
SIZE = DENSITIES[MASTER]

# —— 设计参数（方案 A：极夜蓝）——
PAW_SCALE = 0.73          # 爪子缩放：55%×62% → 40%×45%（对角 261px < 安全圈 264px）
PAW_TOP = (46, 110, 205)  # 爪子渐变顶部（受光）
PAW_BOTTOM = (8, 26, 66)  # 爪子渐变底部（背光）
GLOSS = 70                # 顶部柔光强度

GLASS_ALPHA = 232         # 玻璃底峰值不透明度（双端可见性的平衡点）
GLASS_TINT_TOP = (248, 250, 253)
GLASS_TINT_BOTTOM = (226, 232, 242)


def path_of(kind: str, density: str) -> str:
    return os.path.join(RES, f"drawable-{density}", f"ic_launcher_{kind}.png")


def backup() -> None:
    os.makedirs(BACKUP_DIR, exist_ok=True)
    for d in DENSITIES:
        for kind in ("background", "foreground", "monochrome"):
            src = path_of(kind, d)
            if os.path.exists(src):
                shutil.copy2(src, os.path.join(BACKUP_DIR, f"ic_launcher_{kind}_{d}.png"))
    print(f"已备份原图 → {os.path.relpath(BACKUP_DIR, SCRIPT_DIR)}")


def rrect(size: int, inset: int = 0, radius: float = 0.22):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [inset, inset, size - 1 - inset, size - 1 - inset],
        radius=int(size * radius), fill=255)
    return m


def paw_mask(scale: float = PAW_SCALE):
    """从固化的爪印轮廓源文件读取，等比缩放后居中放回画布。

    注意：必须读 paw_outline_source.png，绝不能读 drawable 里的 foreground。
    foreground 是本脚本的输出，若以它为输入，每次运行都会在上一次结果上
    再缩放一次，爪子会越跑越小（曾发生 0.74 × 0.73 ≈ 0.54 的叠加事故）。
    固化轮廓源可保证本脚本幂等、可重复执行。
    """
    src = os.path.join(SCRIPT_DIR, "paw_outline_source.png")
    if not os.path.exists(src):
        raise FileNotFoundError(f"缺少爪印轮廓源文件: {src}")
    outline = Image.open(src).convert("L")
    nw, nh = int(round(outline.size[0] * scale)), int(round(outline.size[1] * scale))
    crop = outline.resize((nw, nh), Image.LANCZOS)
    canvas = Image.new("L", (SIZE, SIZE), 0)
    canvas.paste(crop, ((SIZE - nw) // 2, (SIZE - nh) // 2))
    return canvas


def glass_background(size: int = SIZE, alpha_peak: int = GLASS_ALPHA) -> Image.Image:
    """玻璃质感底：竖向渐变 + 斜向高光 + 顶部亮描边 + 底部内阴影。"""
    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    shape = rrect(size)
    sf = np.array(shape).astype(np.float32) / 255.0
    yy = np.linspace(0, 1, size, dtype=np.float32)[:, None]

    rgb = np.zeros((size, size, 3), dtype=np.float32)
    for i in range(3):
        rgb[..., i] = GLASS_TINT_TOP[i] + (GLASS_TINT_BOTTOM[i] - GLASS_TINT_TOP[i]) * yy
    body_a = alpha_peak * (1 - 0.10 * yy) * np.ones((1, size), dtype=np.float32)
    body = np.zeros((size, size, 4), dtype=np.uint8)
    body[..., :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    # alpha 必须是「渐变强度 × 圆角遮罩」，只取其一都会出错
    body[..., 3] = (np.clip(body_a, 0, 255) * sf).astype(np.uint8)
    base = Image.alpha_composite(base, Image.fromarray(body, "RGBA"))

    white = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    # 斜向高光：左上主高光 + 右下弱反射
    hl = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(hl)
    d.polygon([(0, 0), (int(size * 0.60), 0), (0, int(size * 0.60))], fill=int(alpha_peak * 0.42))
    d.polygon([(int(size * 0.30), size), (size, int(size * 0.30)), (size, size)],
              fill=int(alpha_peak * 0.16))
    hl = hl.filter(ImageFilter.GaussianBlur(size * 0.05))
    hln = (np.array(hl).astype(np.float32) * sf).astype(np.uint8)
    base = Image.alpha_composite(base, Image.merge(
        "RGBA", (*white.split()[:3], Image.fromarray(hln, "L"))))

    # 边缘：顶部亮描边（玻璃受光）+ 底部暗内阴影（厚度感）
    edge = np.array(shape).astype(np.float32) - np.array(rrect(size, int(size * 0.020))).astype(np.float32)
    top_edge = np.clip(edge * np.clip(1.6 - 2.0 * yy, 0, 1) * 0.85, 0, 255).astype(np.uint8)
    bot_edge = np.clip(edge * np.clip(2.0 * yy - 1.0, 0, 1) * 0.5, 0, 255).astype(np.uint8)
    base = Image.alpha_composite(base, Image.merge(
        "RGBA", (*white.split()[:3], Image.fromarray(top_edge, "L"))))
    shade = Image.new("RGBA", (size, size), (150, 158, 175, 255))
    base = Image.alpha_composite(base, Image.merge(
        "RGBA", (*shade.split()[:3], Image.fromarray(bot_edge, "L"))))
    return base


def vgrad(size: int, top, bottom) -> Image.Image:
    yy = np.linspace(0, 1, size, dtype=np.float32)[:, None]
    a = np.zeros((size, size, 3), dtype=np.float32)
    for i in range(3):
        a[..., i] = top[i] + (bottom[i] - top[i]) * yy
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB")


def paw_foreground(mask: Image.Image) -> Image.Image:
    """极夜蓝竖向渐变爪 + 顶部柔光。"""
    fg = Image.merge("RGBA", (*vgrad(SIZE, PAW_TOP, PAW_BOTTOM).split(), mask))
    yy = np.linspace(0, 1, SIZE, dtype=np.float32)[:, None]
    g = np.clip(GLOSS * (1 - yy * 2.1), 0, 255).astype(np.uint8) * np.ones((1, SIZE), dtype=np.uint8)
    g = np.minimum(g, np.array(mask))
    white = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 255))
    return Image.alpha_composite(fg, Image.merge(
        "RGBA", (*white.split()[:3], Image.fromarray(g, "L"))))


def paw_monochrome(mask: Image.Image) -> Image.Image:
    """主题图标层：纯白剪影，着色交给系统。"""
    white = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 255))
    return Image.merge("RGBA", (*white.split()[:3], mask))


def downsample_premultiplied(img: Image.Image, size: int) -> Image.Image:
    """预乘 alpha 后降采样，避免透明图层边缘发黑。"""
    a = np.array(img, dtype=np.float32)
    al = a[..., 3:4] / 255.0
    premult = np.concatenate([a[..., :3] * al, a[..., 3:4]], axis=-1)
    pm = Image.fromarray(np.clip(premult, 0, 255).astype(np.uint8), "RGBA")
    pm = pm.resize((size, size), Image.LANCZOS)
    r = np.array(pm, dtype=np.float32)
    out_a = np.clip(r[..., 3:4], 1e-6, 255)
    out_rgb = np.clip(r[..., :3] / (out_a / 255.0), 0, 255)
    return Image.fromarray(np.concatenate([out_rgb, out_a], -1).astype(np.uint8), "RGBA")


def main() -> None:
    backup()
    paw = paw_mask(PAW_SCALE)

    masters = {
        "background": glass_background(),
        "foreground": paw_foreground(paw),
        "monochrome": paw_monochrome(paw),
    }

    for kind, img in masters.items():
        for density, size in DENSITIES.items():
            out = img if density == MASTER else downsample_premultiplied(img, size)
            dst = path_of(kind, density)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            out.save(dst, "PNG", optimize=True)
        print(f"  {kind:11s} 已写入 {len(DENSITIES)} 个密度")

    # 验收：爪子尺寸是否落在 66dp 安全圈内（432 画布上直径为 432*66/108 = 264px）
    mn = np.array(paw)
    ys, xs = np.where(mn > 30)
    w, h = xs.max() - xs.min(), ys.max() - ys.min()
    diag = (w ** 2 + h ** 2) ** 0.5
    print(f"\n爪子尺寸: {w}×{h}px（占画布 {w/SIZE*100:.1f}%×{h/SIZE*100:.1f}%）"
          f"  对角 {diag:.0f}px / 安全圈直径 264px  →  {'✅ 安全' if diag <= 264 else '⚠️ 超出'}")


if __name__ == "__main__":
    main()
