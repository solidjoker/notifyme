# -*- coding: utf-8 -*-
# Copyright (c) 2026 solidjoker
# SPDX-License-Identifier: MIT

"""生成 PWA 图标 PNG（微信绿 + 对话气泡 + 分析柱状图）。

产物（相对本文件所在目录）：
  pwa_icons/icon-180.png  Apple touch icon（满幅方形，iOS 自行裁圆角）
  pwa_icons/icon-192.png  manifest 192
  pwa_icons/icon-512.png  manifest 512
  pwa_icons/icon-512-maskable.png  maskable（满幅出血，气泡收在 80% 安全区内）
"""
import os

from PIL import Image, ImageDraw

GREEN = (7, 193, 96, 255)        # #07C160 微信绿
GREEN_DARK = (5, 160, 80, 255)
WHITE = (255, 255, 255, 255)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(BASE_DIR, "pwa_icons")


def draw_bubble(draw, size, cx, cy, bw, bh):
    """以 (cx, cy) 为中心画白色对话气泡 + 绿色柱状图（分析含义）。"""
    rx = bw / 2
    ry = bh / 2
    x0, y0 = cx - rx, cy - ry
    x1, y1 = cx + rx, cy + ry
    radius = bh * 0.32
    # 气泡主体（圆角矩形）
    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=WHITE)
    # 小尾巴（左下）
    tw = bw * 0.16
    th = bh * 0.22
    tx = x0 + bw * 0.28
    draw.polygon([(tx, y1 - radius * 0.4), (tx + tw, y1 - radius * 0.4),
                  (tx - tw * 0.35, y1 + th)], fill=WHITE)
    # 气泡内：三根绿色柱子（由低到高，寓意分析/统计）
    bw_bar = bw * 0.10
    gap = bw * 0.06
    base = y1 - bh * 0.22
    heights = [bh * 0.22, bh * 0.36, bh * 0.50]
    start = cx - (bw_bar * 3 + gap * 2) / 2
    for i, h in enumerate(heights):
        bx = start + i * (bw_bar + gap)
        draw.rounded_rectangle([bx, base - h, bx + bw_bar, base],
                               radius=bw_bar * 0.5, fill=GREEN_DARK)


def make_icon(size, rounded=True, maskable=False):
    """生成一张图标。rounded=True 时画圆角矩形底（普通图标）；
    maskable=True 时满幅出血、气泡缩小到 80% 安全区。"""
    scale = 4  # 超采样抗锯齿
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if rounded:
        d.rounded_rectangle([0, 0, s, s], radius=s * 0.22, fill=GREEN)
    else:
        d.rectangle([0, 0, s, s], fill=GREEN)
    frac = 0.52 if maskable else 0.62   # 气泡占图标比例
    bw = s * frac
    bh = bw * 0.78
    draw_bubble(d, s, s / 2, s * (0.46 if maskable else 0.44), bw, bh)
    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    jobs = [
        ("icon-180.png", 180, False, False),   # apple-touch-icon：满幅方形
        ("icon-192.png", 192, True, False),
        ("icon-512.png", 512, True, False),
        ("icon-512-maskable.png", 512, False, True),
    ]
    for name, size, rounded, maskable in jobs:
        path = os.path.join(OUT_DIR, name)
        make_icon(size, rounded=rounded, maskable=maskable).save(path, "PNG")
        print("生成", path, os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main()
