"""
生成商城前台用的商品图占位 PNG（public/images/*.png）。

为什么用脚本生成而不是放真图：这是演示工程，图片只需要"看得出是商品图、有名字、
有主次色"，不需要真摄影图。用 PIL 画渐变底 + 品名 + 品类标签，15 张不到 1 秒，
换真图时直接覆盖同名文件即可，前后端都不用改代码。

用法：python scripts/gen_product_images.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "front" / "public" / "images"
SIZE = (640, 640)

# slug, 品名, 品类角标, 渐变起止 RGB, 是否秒杀（左上角标换成"1 元秒杀"）
IMAGES = [
    ("p-iphone", "Galaxy S25 Ultra", "手机通讯", (27, 42, 74), (63, 111, 168), False),
    ("p-iphone-alt", "S25 Ultra 侧面", "手机通讯", (36, 52, 79), (84, 119, 159), False),
    ("p-laptop", "Star 14 轻薄本", "电脑办公", (43, 43, 51), (92, 92, 110), False),
    ("p-monitor", "View 27 4K 显示器", "电脑办公", (20, 38, 43), (62, 111, 119), False),
    ("p-mouse", "Click 无线静音鼠标", "数码配件", (51, 36, 59), (110, 76, 126), False),
    ("p-earphone", "Air 降噪耳机", "数码配件", (31, 51, 48), (72, 117, 107), False),
    ("p-watch", "Pulse 智能手表", "智能穿戴", (42, 33, 64), (93, 74, 140), False),
    ("p-fridge", "Frost 十字四门冰箱", "家用电器", (30, 46, 51), (76, 113, 120), False),
    ("p-jacket", "Trail 户外冲锋衣", "服饰运动", (58, 42, 33), (122, 89, 70), False),
    ("p-coffee", "云南精品咖啡豆", "食品生鲜", (59, 42, 29), (138, 92, 54), False),
    ("p-seckill", "1 元秒杀 · 限量专场", "秒杀专场", (140, 31, 31), (224, 83, 58), True),
    ("g-phone", "手机场景图", "场景", (22, 34, 58), (46, 74, 112), False),
    ("g-office", "办公场景图", "场景", (36, 42, 46), (74, 86, 94), False),
    ("g-home", "家居场景图", "场景", (46, 36, 24), (107, 86, 54), False),
    ("g-deal", "大促场景图", "场景", (122, 31, 61), (194, 86, 75), False),
]


def find_cjk_font(size: int) -> ImageFont.FreeTypeFont:
    """Windows 自带的中文字体优先，找不到退回 PIL 默认位图字体。"""
    for name in ("msyhbd.ttc", "msyh.ttc", "simhei.ttf", "NotoSansCJK-Bold.ttc", "arialbd.ttf"):
        path = Path("C:/Windows/Fonts") / name
        if path.exists():
            try:
                return ImageFont.truetype(str(path), size)
            except OSError:
                continue
    return ImageFont.load_default()


def rounded(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def build(slug, label, tag, from_rgb, to_rgb, seckill):
    w, h = SIZE
    img = Image.new("RGB", (w, h), from_rgb)
    draw = ImageDraw.Draw(img)

    # 竖向渐变底：逐行插值，比纯色块更像电商主图
    for y in range(h):
        t = y / (h - 1)
        row = tuple(int(a + (b - a) * t) for a, b in zip(from_rgb, to_rgb))
        draw.line([(0, y), (w, y)], fill=row)

    # 居中"产品框"，暗示这是一张商品图而不是色块
    box = (90, 150, w - 90, h - 190)
    rounded(draw, box, 26, fill=(255, 255, 255, 28))
    draw.rectangle([b for b in box], outline=(255, 255, 255, 90), width=3)
    draw.line([(box[0] + 40, box[3] - 60), (box[2] - 40, box[3] - 60)], fill=(255, 255, 255, 70), width=3)

    title = find_cjk_font(46)
    small = find_cjk_font(30)
    micro = find_cjk_font(24)

    tw = draw.textlength(label, font=title)
    while tw > box[2] - box[0] - 30 and title.size > 22:
        title = find_cjk_font(title.size - 4)
        tw = draw.textlength(label, font=title)
    draw.text(((w - tw) / 2, (box[1] + box[3]) / 2 - 40), label, font=title, fill=(255, 255, 255))
    tagw = draw.textlength(tag, font=micro)
    draw.text(((w - tagw) / 2, (box[1] + box[3]) / 2 + 24), tag, font=micro, fill=(255, 255, 255, 190))

    # 左上角品类标
    badge = ("¥ 自营" if not seckill else "1 元秒杀")
    bw = draw.textlength(badge, font=small)
    rounded(draw, (24, 24, 24 + bw + 34, 92), 12, fill=(216, 34, 46))
    draw.text((24 + 17, 40), badge, font=small, fill=(255, 255, 255))

    # 底部说明，表明是演示占位图
    note = "Galaxy Mall 演示占位图 · scripts/gen_product_images.py 生成"
    nw = draw.textlength(note, font=micro)
    draw.text(((w - nw) / 2, h - 78), note, font=micro, fill=(255, 255, 255, 150))

    img.save(OUT / f"{slug}.png", optimize=True)
    return (OUT / f"{slug}.png").stat().st_size


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for spec in IMAGES:
        size = build(*spec)
        total += size
        print(f"  {spec[0]:<16} {spec[1]:<24} {size / 1024:6.1f} KB")
    print(f"\n共 {len(IMAGES)} 张 -> {OUT}  合计 {total / 1024:.1f} KB")


if __name__ == "__main__":
    main()
