"""生成 LanDrop 应用图标（多尺寸 ICO，含 256 大图）。"""
import os
import struct
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "assets", "app.ico")

BG = (16, 18, 21, 255)
ACCENT = (76, 141, 246, 255)
GREEN = (87, 201, 138, 255)
WHITE = (240, 244, 250, 255)


def render(size):
    s = size * 4  # 超采样
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(s * 0.22)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=BG)

    # 右箭头（发送）
    cy = s * 0.46
    ax0, ax1 = s * 0.20, s * 0.60
    h = s * 0.115
    d.polygon([(ax0, cy - h), (ax1 - h, cy - h), (ax1 - h, cy - 2.1 * h), (ax1 + h * 0.9, cy),
               (ax1 - h, cy + 2.1 * h), (ax1 - h, cy + h), (ax0, cy + h)], fill=ACCENT)

    # 底部两条短线（接收/落地）
    d.rounded_rectangle([s * 0.22, s * 0.70, s * 0.52, s * 0.765], radius=s * 0.022, fill=GREEN)
    d.rounded_rectangle([s * 0.22, s * 0.79, s * 0.42, s * 0.855], radius=s * 0.022,
                        fill=(154, 161, 172, 255))

    return img.resize((size, size), Image.LANCZOS)


def main():
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = [render(n) for n in sizes]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    # 手写 ICO：PNG 压缩的 256 图 + BMP 小图
    blobs = []
    for im in images:
        if im.size[0] >= 256:
            import io
            buf = io.BytesIO()
            im.save(buf, format="PNG")
            blobs.append((im.size[0], buf.getvalue(), True))
        else:
            import io
            buf = io.BytesIO()
            im.save(buf, format="BMP")
            data = buf.getvalue()
            # 去掉 BMP 文件头 14 字节，得到 DIB
            blobs.append((im.size[0], data[14:], False))

    header = struct.pack("<HHH", 0, 1, len(blobs))
    offset = 6 + 16 * len(blobs)
    entries = b""
    body = b""
    for size, data, is_png in blobs:
        w = 0 if size >= 256 else size
        h = 0 if size >= 256 else size
        entries += struct.pack("<BBBBHHII", w, h, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
        body += data
    with open(OUT, "wb") as f:
        f.write(header + entries + body)
    print("wrote", os.path.abspath(OUT), os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
