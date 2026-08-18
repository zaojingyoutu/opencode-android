#!/usr/bin/env python3
"""生成 Android 启动图标：圆角矩形深蓝背景 + 居中白色 >_ 符号。"""

from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

# 颜色
BG_COLOR = (13, 59, 115)   # 深蓝 (#0D3B73)
FG_COLOR = (255, 255, 255)  # 白色

# 密度 → 尺寸
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# 基础尺寸（用于绘制 + 缩放）
BASE = max(SIZES.values())  # 192

# 项目路径
RES_DIR = Path(__file__).resolve().parent.parent / "app/src/main/res"


def find_font(size: int) -> ImageFont.FreeTypeFont:
    """查找可用的字体，优先使用系统字体。"""
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/TTF/DejaVuSansMono-Bold.ttf",
        "/usr/share/fonts/dejavu/DejaVuSansMono-Bold.ttf",
        "/usr/share/fonts/TTF/NotoSansMono-Regular.ttf",
        "/usr/share/fonts/noto/Noto Sans Mono/NotoSansMono-Regular.ttf",
        "/System/Library/Fonts/Menlo.ttc",
    ]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except (FileNotFoundError, OSError):
            continue
    # 回退到默认字体
    return ImageFont.load_default()


def draw_base_icon() -> Image.Image:
    """在 BASE×BASE 画布上绘制基础图标。"""
    img = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # 圆角矩形背景
    radius = int(BASE * 0.22)
    draw.rounded_rectangle(
        [0, 0, BASE - 1, BASE - 1],
        radius=radius,
        fill=BG_COLOR,
    )

    # 居中绘制 ">_" 符号
    font_size = int(BASE * 0.52)
    font = find_font(font_size)

    text = ">_"
    bbox = draw.textbbox((0, 0), text, font=font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    x = (BASE - text_w) // 2
    y = (BASE - text_h) // 2
    draw.text((x, y), text, font=font, fill=FG_COLOR)

    return img


def main() -> None:
    base_icon = draw_base_icon()
    print(f"✓ 基础图标已生成 ({BASE}×{BASE})")

    for density, size in SIZES.items():
        if size == BASE:
            icon = base_icon
        else:
            icon = base_icon.resize((size, size), Image.LANCZOS)

        out_dir = RES_DIR / density
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / "ic_launcher.png"
        icon.save(out_path, "PNG")
        print(f"✓ {density}/ic_launcher.png → {size}×{size}")


if __name__ == "__main__":
    main()
