#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
启动器图标重设计：透明玻璃底 + 多色猫爪（深蓝垫 + 青趾）。

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
1. foreground：沿用现有爪印轮廓，把爪子拆成「爪垫 + 左/中/右趾」4 个连通域分别
   缩放（依然 73%，确保对角 261 < 安全圈 264）。
   - 爪垫（最大连通域）= 深蓝竖向渐变 + 顶部柔光，保对比度。
   - 3 个趾头 = 青/蓝绿竖向渐变 + 顶部柔光，作为强调色。
   多色对比源自「主体深 + 强调色」搭配，参照 Discord / Telegram 的设计语言。
2. background：玻璃质感底（渐变 / 斜向高光 / 顶亮描边 / 底内阴影），保持不变。
3. monochrome：白色 + alpha 纯剪影（Android 主题图标按此层由系统着色），
   4 个部件分别填充同一白色 + 同 alpha，确保主题图标形状与常规图标一致。

产出：以 xxxhdpi(432) 为主图，按「预乘 alpha」降采样到各密度写回，
避免透明图层边缘发黑。原始文件先备份到 image/icon_backup_<时间戳>/。
"""
import os
import shutil
from collections import deque
from datetime import datetime

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(SCRIPT_DIR), "Bastion", "app", "src", "main", "res")
BACKUP_DIR = os.path.join(SCRIPT_DIR, f"icon_backup_{datetime.now():%Y%m%d_%H%M%S}")

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
MASTER = "xxxhdpi"
SIZE = DENSITIES[MASTER]

# —— 设计参数（多色方案：深蓝垫 + 青趾）——
PAW_SCALE = 0.73          # 爪子缩放（沿用，与现状一致）

# 爪垫（最大连通域）：深蓝竖向渐变 + 顶部柔光。深底保证深色桌面下爪子仍清晰。
PAD_TOP = (37, 99, 235)    # 顶部受光（亮一点的电光蓝）
PAD_BOTTOM = (8, 22, 60)   # 底部背光（极夜蓝）

# 三趾：青绿竖向渐变 + 顶部柔光。亮色对比，又不至于喧宾夺主。
TOE_TOP = (94, 234, 212)   # 顶部受光（青）
TOE_BOTTOM = (13, 148, 136) # 底部背光（墨绿青）
# 中趾稍亮一档强调中心对称
TOE_MID_TOP = (165, 243, 252)
TOE_MID_BOTTOM = (20, 184, 166)

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


def _split_paw_parts() -> dict:
    """连通域拆分爪印源图（爪垫 + 左/中/右趾），返回原尺寸 L 模式 mask 字典。

    关键：必须读 paw_outline_source.png，绝不能读 drawable 里的 foreground，
    否则会随每次运行叠加缩放。详见 [paw_masks_for_canvas]。
    """
    src = Image.open(os.path.join(SCRIPT_DIR, "paw_outline_source.png")).convert("L")
    a = np.array(src)
    h, w = a.shape
    fg = a > 128
    lab = -np.ones((h, w), dtype=np.int32)
    comps = []
    for sy in range(h):
        for sx in range(w):
            if not fg[sy, sx] or lab[sy, sx] >= 0:
                continue
            cid = len(comps)
            q = deque([(sy, sx)])
            lab[sy, sx] = cid
            px = []
            while q:
                y, x = q.popleft()
                px.append((y, x))
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and fg[ny, nx] and lab[ny, nx] < 0:
                        lab[ny, nx] = cid
                        q.append((ny, nx))
            comps.append(px)

    comps.sort(key=len, reverse=True)
    if len(comps) < 4:
        raise RuntimeError(f"期望至少 4 个连通域（垫+3 趾），实得 {len(comps)}")
    pad, toes = comps[0], comps[1:4]

    def to_mask(px):
        m = Image.new("L", (w, h), 0)
        d = ImageDraw.Draw(m)
        d.point(px, fill=255)
        return m.point(lambda v: 255 if v > 0 else 0)

    pad_mask = to_mask(pad)
    # 3 个趾按中心 x 排序 → 左 / 中 / 右
    toe_masks = []
    for px in toes:
        xs = [p[1] for p in px]
        cx = sum(xs) / len(xs)
        toe_masks.append((cx, to_mask(px)))
    toe_masks.sort(key=lambda t: t[0])
    return {
        "pad": pad_mask,
        "toe_left": toe_masks[0][1],
        "toe_mid": toe_masks[1][1],
        "toe_right": toe_masks[2][1],
    }


def paw_masks_for_canvas(scale: float = PAW_SCALE) -> dict:
    """从固化的爪印轮廓源文件读取，连通域拆分后等比缩放居中放回画布。

    注意：必须读 paw_outline_source.png，绝不能读 drawable 里的 foreground。
    foreground 是本脚本的输出，若以它为输入，每次运行都会在上一次结果上
    再缩放一次，爪子会越跑越小（曾发生 0.74 × 0.73 ≈ 0.54 的叠加事故）。
    固化轮廓源可保证本脚本幂等、可重复执行。
    """
    parts = _split_paw_parts()
    out = {}
    for k, mask in parts.items():
        nw = int(round(mask.size[0] * scale))
        nh = int(round(mask.size[1] * scale))
        crop = mask.resize((nw, nh), Image.LANCZOS)
        canvas = Image.new("L", (SIZE, SIZE), 0)
        canvas.paste(crop, ((SIZE - nw) // 2, (SIZE - nh) // 2))
        out[k] = canvas
    return out


# 向后兼容：旧 API 单 mask 版
def paw_mask(scale: float = PAW_SCALE) -> Image.Image:
    parts = paw_masks_for_canvas(scale)
    return parts["pad"]


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


def _apply_gloss(img: Image.Image) -> Image.Image:
    """在已有 RGBA 图上叠顶部柔光（柔光 alpha 取 min(柔光, 原图 alpha)）。"""
    yy = np.linspace(0, 1, SIZE, dtype=np.float32)[:, None]
    g = np.clip(GLOSS * (1 - yy * 2.1), 0, 255).astype(np.uint8) * np.ones((1, SIZE), dtype=np.uint8)
    g = np.minimum(g, np.array(img.split()[3]))
    white = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 255))
    return Image.alpha_composite(img, Image.merge(
        "RGBA", (*white.split()[:3], Image.fromarray(g, "L"))))


def paw_foreground(parts: dict) -> Image.Image:
    """多色爪：爪垫深蓝 + 3 趾青色，按 alpha 从大到小叠加避免接缝。"""
    layer_specs = [
        (parts["pad"], PAD_TOP, PAD_BOTTOM),
        (parts["toe_left"], TOE_TOP, TOE_BOTTOM),
        (parts["toe_mid"], TOE_MID_TOP, TOE_MID_BOTTOM),
        (parts["toe_right"], TOE_TOP, TOE_BOTTOM),
    ]
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    # 按 alpha 总能量降序叠加，让浅色趾头最后画在最上层；
    # 但其实 4 个部件在源图里互不相邻（连通域分离），顺序无视觉差异。
    for mask, top, bottom in layer_specs:
        layer = Image.merge("RGBA", (*vgrad(SIZE, top, bottom).split(), mask))
        out = Image.alpha_composite(out, layer)
    return _apply_gloss(out)


def paw_monochrome(parts: dict) -> Image.Image:
    """主题图标层：4 个部件分别填纯白剪影，着色交给系统。"""
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    white = Image.new("RGBA", (SIZE, SIZE), (255, 255, 255, 255))
    for k in ("pad", "toe_left", "toe_mid", "toe_right"):
        out = Image.alpha_composite(out, Image.merge("RGBA", (*white.split()[:3], parts[k])))
    return out


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
    parts = paw_masks_for_canvas(PAW_SCALE)

    masters = {
        "background": glass_background(),
        "foreground": paw_foreground(parts),
        "monochrome": paw_monochrome(parts),
    }

    for kind, img in masters.items():
        for density, size in DENSITIES.items():
            out = img if density == MASTER else downsample_premultiplied(img, size)
            dst = path_of(kind, density)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            out.save(dst, "PNG", optimize=True)
        print(f"  {kind:11s} 已写入 {len(DENSITIES)} 个密度")

    # 验收：爪子整体尺寸是否落在 66dp 安全圈内（432 画布上直径为 432*66/108 = 264px）
    # 4 个部件合并后取 bbox
    combined = np.zeros((SIZE, SIZE), dtype=np.uint8)
    for m in parts.values():
        combined = np.maximum(combined, np.array(m))
    ys, xs = np.where(combined > 30)
    if len(xs) == 0:
        print("\n警告：合并后无前景像素")
    else:
        w, h = xs.max() - xs.min(), ys.max() - ys.min()
        diag = (w ** 2 + h ** 2) ** 0.5
        print(f"\n爪子尺寸: {w}×{h}px（占画布 {w/SIZE*100:.1f}%×{h/SIZE*100:.1f}%）"
              f"  对角 {diag:.0f}px / 安全圈直径 264px  →  {'✅ 安全' if diag <= 264 else '⚠️ 超出'}")


if __name__ == "__main__":
    main()

