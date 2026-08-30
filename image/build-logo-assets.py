#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""bastion 品牌图形生成器：logo + README hero（浅色 / 深色双版）。

设计母题：
    猫爪  = 被守护的东西
    盾牌  = 守护者
    Safety first = 口号

产出（全部落在 image/ 下）：
    bastion-logo.svg        512x512 透明底，内嵌 prefers-color-scheme 自适应（适合官网内联 / 任意缩放）
    bastion-hero-light.svg  1280x420 浅色底，给 GitHub 浅色主题
    bastion-hero-dark.svg   1280x420 深蓝底，给 GitHub 深色主题（沿用旧 hero 的品牌色 #0A2545 / #F5B942）

为什么 hero 要拆成两个文件而不是一个自适应文件：
    GitHub 的主题是站内开关，跟操作系统的 prefers-color-scheme 不一定一致。
    README 里用 `#gh-light-mode-only` / `#gh-dark-mode-only` 才是官方支持、结果确定的做法。
    单文件自适应版（bastion-logo.svg）留给官网内联使用。

改色 / 改尺寸：改下面的调色板与画布常量，重跑
    python3 image/build-logo-assets.py
三个文件一次性同步更新，不会出现版本漂移。

注：本文件是对原位图 logo 的**矢量重绘**（非描摹），因此原图右下角的 "AI 生成" 水印不存在。
"""

from pathlib import Path
import xml.etree.ElementTree as ET

OUT_DIR = Path(__file__).resolve().parent

# ---------------------------------------------------------------- 几何（局部坐标，盾牌 208x214，中心 x=120）
SHIELD_D = (
    "M 34 16 H 206 A 18 18 0 0 1 224 34 V 116 "
    "C 224 170 182 208 120 230 C 58 208 16 170 16 116 V 34 A 18 18 0 0 1 34 16 Z"
)

# 掌垫：上宽下窄，底部两个圆瓣 + 中间浅缺口（心形感，是猫爪掌垫最好认的轮廓）
PAD_D = (
    "M 120 126 C 96 126 79 145 79 166 "
    "C 79 181 88 193 100 197 C 109 200 114 195 120 188 "
    "C 126 195 131 200 140 197 C 152 193 161 181 161 166 "
    "C 161 145 144 126 120 126 Z"
)

# 四个趾垫：(cx, cy, 旋转角)
TOES = ((70, 102, -24), (101, 76, -9), (139, 76, 9), (170, 102, 24))

FONT_STACK = (
    "Quicksand, Nunito, 'Avenir Next', 'Segoe UI', system-ui, "
    "-apple-system, 'Helvetica Neue', Arial, sans-serif"
)

GOLD = ("#FFE6A8", "#F5B942", "#B9760F")  # 沿用旧 hero 的金色渐变

# ---------------------------------------------------------------- 画布常量
LOGO_BOX = 512
LOGO_MARK_SCALE = 1.15
LOGO_MARK_TOP = 84.0
LOGO_TEXT_BASELINE = 404

HERO_W, HERO_H = 1280, 420
HERO_MARK_SCALE = 0.78
HERO_MARK_TOP = 64.0
HERO_TITLE_BASELINE = 310
HERO_SUB_BASELINE = 362

TITLE_TEXT = "BASTION"
SUB_TEXT = "SAFETY FIRST &#183; LOCAL-FIRST &#183; OPEN SOURCE"
LOGO_TEXT = "Safety first"


# ---------------------------------------------------------------- 调色板
def light_palette():
    return {
        "gold": GOLD,
        "paw": "#0F1B2D",          # 近黑，对应原图黑线
        "title": "#0A2545",
        "sub": "#33506E",
        "bg": ("#FDFCFA", "#F8F5EF", "#F3EFE6"),
        "grid": "#0A2545",
        "grid_op": "0.045",
        "top_light": ("#F5B942", "0.10"),
        "halo_op": "0.16",
        "ring_in": ("#0A2545", "0.10"),
        "ring_out": ("#B9760F", "0.34"),
        "glow_op": "0.16",
    }


def dark_palette():
    return {
        "gold": GOLD,
        "paw": "#F4F8FF",          # 深底上改用米白，黑爪在深蓝底上会糊掉
        "title": "#FFFFFF",
        "sub": "#9FD4FF",
        "bg": ("#0A2545", "#0B2E52", "#061426"),
        "grid": "#9FD4FF",
        "grid_op": "0.045",
        "top_light": ("#9FD4FF", "0.13"),
        "halo_op": "0.20",
        "ring_in": ("#9FD4FF", "0.14"),
        "ring_out": ("#F5B942", "0.22"),
        "glow_op": "0.30",
    }


def adaptive_palette():
    """logo 单文件版：金色恒定（深浅底都看得见），墨色用 CSS 变量随主题翻转。"""
    return {
        "gold": GOLD,
        "paw": "var(--paw)",
        "title": "var(--text)",
        "sub": "var(--text)",
        "bg": None,
        "grid": None,
        "grid_op": "0",
        "top_light": None,
        "halo_op": "0",
        "ring_in": None,
        "ring_out": None,
        "glow_op": "0",
    }


# ---------------------------------------------------------------- 片段
def gold_gradient(gid, stops):
    return (
        f'<linearGradient id="{gid}" x1="0" y1="0" x2="0" y2="1">\n'
        f'      <stop offset="0%" stop-color="{stops[0]}"/>\n'
        f'      <stop offset="48%" stop-color="{stops[1]}"/>\n'
        f'      <stop offset="100%" stop-color="{stops[2]}"/>\n'
        f'    </linearGradient>'
    )


def mark_body(pal, gid="gold", sid="shield", glow=False):
    """盾牌描边（金）+ 爪印（墨）。glow=True 时在盾牌下垫一层柔光。"""
    toes = "\n".join(
        f'        <ellipse cx="{x}" cy="{y}" rx="15.5" ry="19.5" '
        f'transform="rotate({r} {x} {y})"/>'
        for x, y, r in TOES
    )
    parts = []
    if glow:
        parts.append(
            f'      <use href="#{sid}" fill="none" stroke="{pal["gold"][1]}" '
            f'stroke-opacity="{pal["glow_op"]}" stroke-width="22" filter="url(#soft)"/>'
        )
    parts.append(
        f'      <use href="#{sid}" fill="none" stroke="url(#{gid})" '
        f'stroke-width="11" stroke-linejoin="round"/>'
    )
    parts.append(
        f'      <g fill="{pal["paw"]}">\n'
        f'        <path d="{PAD_D}"/>\n'
        f'{toes}\n'
        f'      </g>'
    )
    return "\n".join(parts)


# ---------------------------------------------------------------- 1) logo（512，透明底，自适应）
def build_logo():
    pal = adaptive_palette()
    tx = LOGO_BOX / 2 - 120 * LOGO_MARK_SCALE
    css = """    svg {
      --paw: #0F1B2D;
      --text: #0F1B2D;
    }
    @media (prefers-color-scheme: dark) {
      svg {
        --paw: #F4F8FF;
        --text: #F4F8FF;
      }
    }"""
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {LOGO_BOX} {LOGO_BOX}"
     width="{LOGO_BOX}" height="{LOGO_BOX}" role="img"
     aria-label="Bastion — a paw guarded by a shield, Safety first">
  <title>Bastion — Safety first</title>

  <style>
{css}
  </style>

  <defs>
    {gold_gradient("gold", pal["gold"])}
    <path id="shield" d="{SHIELD_D}"/>
  </defs>

  <!-- 盾牌 + 猫爪 -->
  <g transform="translate({tx:.2f},{LOGO_MARK_TOP}) scale({LOGO_MARK_SCALE})">
{mark_body(pal)}
  </g>

  <!-- 口号：改用几何无衬线，避免手写体在缩略图上被误读成 Sarfity / Salfity -->
  <text x="{LOGO_BOX / 2:.0f}" y="{LOGO_TEXT_BASELINE}" text-anchor="middle"
        font-family="{FONT_STACK}" font-size="52" font-weight="700"
        letter-spacing="1.5" fill="{pal['title']}">{LOGO_TEXT}</text>
</svg>
"""


# ---------------------------------------------------------------- 2) hero（1280x420，浅/深各一版）
def build_hero(pal, label):
    mark_h = 214 * HERO_MARK_SCALE
    mark_w = 208 * HERO_MARK_SCALE
    tx = HERO_W / 2 - 120 * HERO_MARK_SCALE
    ty = HERO_MARK_TOP - 16 * HERO_MARK_SCALE
    cy = HERO_MARK_TOP + mark_h / 2          # 光晕 / 防护环的圆心

    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {HERO_W} {HERO_H}"
     width="100%" preserveAspectRatio="xMidYMid meet" role="img"
     aria-label="Bastion — {label}">
  <title>Bastion — {label}</title>

  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="{pal['bg'][0]}"/>
      <stop offset="48%" stop-color="{pal['bg'][1]}"/>
      <stop offset="100%" stop-color="{pal['bg'][2]}"/>
    </linearGradient>

    <radialGradient id="halo" cx="50%" cy="50%" r="50%">
      <stop offset="0%" stop-color="{pal['gold'][1]}" stop-opacity="{pal['halo_op']}"/>
      <stop offset="45%" stop-color="{pal['gold'][1]}" stop-opacity="0.07"/>
      <stop offset="100%" stop-color="{pal['gold'][1]}" stop-opacity="0"/>
    </radialGradient>

    <radialGradient id="topLight" cx="50%" cy="0%" r="62%">
      <stop offset="0%" stop-color="{pal['top_light'][0]}" stop-opacity="{pal['top_light'][1]}"/>
      <stop offset="100%" stop-color="{pal['top_light'][0]}" stop-opacity="0"/>
    </radialGradient>

    {gold_gradient("gold", pal["gold"])}

    <filter id="soft" x="-60%" y="-60%" width="220%" height="220%">
      <feGaussianBlur stdDeviation="8"/>
    </filter>

    <path id="shield" d="{SHIELD_D}"/>
  </defs>

  <!-- 画布 -->
  <rect width="{HERO_W}" height="{HERO_H}" fill="url(#bg)"/>
  <rect width="{HERO_W}" height="{HERO_H}" fill="url(#topLight)"/>

  <!-- 质感底纹 -->
  <g stroke="{pal['grid']}" stroke-opacity="{pal['grid_op']}" stroke-width="1">
    <path d="M0 70H1280M0 140H1280M0 210H1280M0 280H1280M0 350H1280"/>
    <path d="M160 0V420M320 0V420M480 0V420M640 0V420M800 0V420M960 0V420M1120 0V420"/>
  </g>

  <!-- 金色辉光 + 两层防护环，暗示「层层设防」 -->
  <ellipse cx="{HERO_W / 2:.0f}" cy="{cy:.0f}" rx="230" ry="175" fill="url(#halo)"/>
  <circle cx="{HERO_W / 2:.0f}" cy="{cy:.0f}" r="152" fill="none" stroke="{pal['ring_in'][0]}"
          stroke-opacity="{pal['ring_in'][1]}" stroke-width="1.5"/>
  <circle cx="{HERO_W / 2:.0f}" cy="{cy:.0f}" r="172" fill="none" stroke="{pal['ring_out'][0]}"
          stroke-opacity="{pal['ring_out'][1]}" stroke-width="1"
          stroke-dasharray="3 13" stroke-linecap="round"/>

  <!-- 盾牌 + 猫爪 -->
  <g transform="translate({tx:.2f},{ty:.2f}) scale({HERO_MARK_SCALE})">
{mark_body(pal, glow=True)}
  </g>

  <!-- 字标与口号（纯英文，避免字体缺失导致方块） -->
  <g text-anchor="middle" font-family="{FONT_STACK}">
    <text x="{HERO_W / 2:.0f}" y="{HERO_TITLE_BASELINE}" fill="{pal['title']}"
          font-size="60" font-weight="800" letter-spacing="14">{TITLE_TEXT}</text>
    <rect x="{HERO_W / 2 - 44:.0f}" y="{HERO_TITLE_BASELINE + 16}" width="88" height="2.5"
          rx="1.25" fill="url(#gold)" opacity="0.85"/>
    <text x="{HERO_W / 2:.0f}" y="{HERO_SUB_BASELINE}" fill="{pal['sub']}"
          font-size="15" font-weight="600" letter-spacing="6.5" opacity="0.9">{SUB_TEXT}</text>
  </g>
</svg>
"""


# ---------------------------------------------------------------- 写出 + 自检
def write(name: str, content: str) -> None:
    path = OUT_DIR / name
    path.write_text(content, encoding="utf-8")
    # XML 良构性自检：SVG 一旦标签没闭合，浏览器会整张不渲染
    ET.fromstring(content.encode("utf-8"))
    print(f"  {name:<26} {len(content.encode('utf-8')):>6} bytes  XML OK")


def main() -> None:
    print("生成 bastion 品牌图形：")
    write("bastion-logo.svg", build_logo())
    write("bastion-hero-light.svg", build_hero(light_palette(), "local-first, open-source password fortress"))
    write("bastion-hero-dark.svg", build_hero(dark_palette(), "local-first, open-source password fortress"))
    print("完成。")


if __name__ == "__main__":
    main()
