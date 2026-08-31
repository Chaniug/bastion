#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成启动器图标「旧 | 新」并排对比图，供肉眼验收。"""
import os

from PIL import Image

RES = r"D:\Bastion\bastion\Bastion\app\src\main\res"
BK = r"D:\Bastion\bastion\image\icon_backup_original"
NEW_DIR = os.path.join(RES, "drawable-xxxhdpi")
OUT = r"D:\Bastion\bastion\image\icon_preview_compare.png"


def compose(bg_dir, fg_dir, bg_name, fg_name):
    bg = Image.open(os.path.join(bg_dir, bg_name)).convert("RGBA")
    fg = Image.open(os.path.join(fg_dir, fg_name)).convert("RGBA")
    return Image.alpha_composite(bg, fg)


old = compose(BK, BK, "ic_launcher_background_xxxhdpi.png", "ic_launcher_foreground_xxxhdpi.png")
new = compose(
    NEW_DIR,
    NEW_DIR,
    "ic_launcher_background.png",
    "ic_launcher_foreground.png",
)

w, h = new.size
gap = 40
canvas = Image.new("RGBA", (w * 2 + gap, h + 60), (58, 58, 58, 255))
canvas.paste(old, (0, 30), old)
canvas.paste(new, (w + gap, 30), new)
canvas.save(OUT, "PNG")
print("old size:", old.size, "new size:", new.size)
print("saved:", OUT)
