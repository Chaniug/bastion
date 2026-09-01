#!/usr/bin/env python3
"""图标几何自检：方向 / 居中 / 左右对称 / 黄金分割 / 安全圈 / monochrome 规范。

用法：
    python3.11 image/verify_icon.py

设计要点：
* 不靠连通域去猜哪个块是肉垫 —— mdpi(108px) 下三个趾会和肉垫粘连成一个域，
  必然误判。这里直接复用 revamp_glass_icon 内部的 4 个精确 mask 作为参考，
  再与各密度 PNG 的 alpha 分布比对。
* 转置检测单独列一项。曾因 ImageDraw.point() 的坐标顺序把爪子转置 90°，
  整体质心却仍然居中，普通「居中检查」完全发现不了 —— 必须查包围盒宽高比。

退出码 0 = 全部通过，1 = 有不合格项。
"""
from __future__ import annotations

import os
import sys

import numpy as np
from PIL import Image

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, SCRIPT_DIR)

import revamp_glass_icon as R  # noqa: E402

PHI = (1 + 5 ** 0.5) / 2
SAFE_CIRCLE_DIAMETER = 264          # 432 画布上 66dp 安全圈直径
DENSITIES = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
             ("xxhdpi", 324), ("xxxhdpi", 432)]
SIZE = R.SIZE


def fg_path(density: str) -> str:
    return os.path.join(REPO, "Bastion", "app", "src", "main", "res",
                        f"drawable-{density}", "ic_launcher_foreground.png")


def mono_path(density: str) -> str:
    return os.path.join(REPO, "Bastion", "app", "src", "main", "res",
                        f"drawable-{density}", "ic_launcher_monochrome.png")


def alpha_of(path: str) -> np.ndarray:
    return np.array(Image.open(path).convert("RGBA"))[..., 3].astype(np.float32)


def centroid(a: np.ndarray) -> tuple[float, float]:
    h, w = a.shape
    yy, xx = np.meshgrid(np.arange(h), np.arange(w), indexing="ij")
    s = a.sum()
    return float((a * xx).sum() / s), float((a * yy).sum() / s)


def check_reference(parts: dict) -> bool:
    """432 主图：脚趾必须在肉垫上方，且左右对称、肉垫与中趾竖直对齐。"""
    print("【参考几何】432 画布，脚本内部精确 mask")
    ref = {}
    for k, m in parts.items():
        a = np.array(m).astype(np.float32)
        cx, cy = centroid(a)
        ref[k] = (cx, cy)
        print(f"  {k:<10} 质心=({cx:6.1f}, {cy:6.1f})")

    pad_y = ref["pad"][1]
    toe_y = (ref["toe_left"][1] + ref["toe_mid"][1] + ref["toe_right"][1]) / 3
    ok_dir = toe_y < pad_y
    print(f"\n  脚趾平均 y={toe_y:.1f} vs 肉垫 y={pad_y:.1f} → 脚趾在肉垫"
          f"{'上方 ✅' if ok_dir else '下方 ❌（上下颠倒）'}")

    if ok_dir:
        dl = abs(ref["toe_left"][0] - ref["toe_mid"][0])
        dr = abs(ref["toe_right"][0] - ref["toe_mid"][0])
        ok_sym = abs(dl - dr) < 1.0
        ok_align = abs(ref["pad"][0] - ref["toe_mid"][0]) < 1.0
        print(f"  左右对称 |左-中|={dl:.1f} |右-中|={dr:.1f} 差={abs(dl - dr):.2f}px "
              f"{'✅' if ok_sym else '❌'}")
        print(f"  肉垫与中趾竖直对齐偏差 {abs(ref['pad'][0] - ref['toe_mid'][0]):.2f}px "
              f"{'✅' if ok_align else '❌'}")
        return ok_sym and ok_align
    return False


def check_densities() -> bool:
    print("\n【各密度几何】对角 / 居中 / 宽高比（转置检测）")
    print(f"  {'密度':<9}{'对角/目标':>16}{'偏心(x,y)':>18}{'包围盒':>12}  判定")
    all_ok = True
    for name, size in DENSITIES:
        a = alpha_of(fg_path(name))
        r = size / SIZE
        cx, cy = centroid(a)
        off = (cx - size / 2, cy - size / 2)
        ys, xs = np.nonzero(a > 8)
        bw, bh = int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)
        diag = (bw ** 2 + bh ** 2) ** 0.5
        target = SAFE_CIRCLE_DIAMETER / PHI * r

        ok_diag = abs(diag - target) <= max(3.0, target * 0.05)
        ok_off = abs(off[0]) <= 2.0 and abs(off[1]) <= 2.0
        ok_ar = bh >= bw           # 爪印竖向：高 >= 宽；转置后会变成宽 > 高
        ok = ok_diag and ok_off and ok_ar
        all_ok &= ok
        notes = "".join([
            "" if ok_diag else " 尺寸偏离",
            "" if ok_off else " 未居中",
            "" if ok_ar else " 疑似转置!",
        ])
        print(f"  {name:<9}{f'{diag:.1f}/{target:.1f}':>16}"
              f"{f'({off[0]:+.1f},{off[1]:+.1f})':>18}{f'{bw}×{bh}':>12}"
              f"  {'✅' if ok else '❌'}{notes}")
    return all_ok


def check_monochrome() -> bool:
    print("\n【monochrome 层】规范要求：白色 + alpha 纯剪影")
    all_ok = True
    for name, size in DENSITIES:
        arr = np.array(Image.open(mono_path(name)).convert("RGBA"))
        al, rgb = arr[..., 3], arr[..., :3]
        m = al > 8
        ok_white = bool((rgb[m] == 255).all())
        cx, cy = centroid(al.astype(np.float32))
        ok_off = abs(cx - size / 2) <= 2.0 and abs(cy - size / 2) <= 2.0
        ok = ok_white and ok_off
        all_ok &= ok
        print(f"  {name:<9}不透明像素全白={'是' if ok_white else '否'}  "
              f"偏心({cx - size / 2:+.1f},{cy - size / 2:+.1f})  {'✅' if ok else '❌'}")
    return all_ok


def main() -> int:
    parts = R.paw_masks_for_canvas()
    ok = True
    ok &= check_reference(parts)
    ok &= check_densities()
    ok &= check_monochrome()
    print("\n" + "=" * 62)
    print("图标几何自检全部通过 ✅" if ok else "图标几何自检未通过 ❌")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
