#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成一个最小但合法的 EPUB 2 测试文件，用于验证移植后的 EPUB 导入链路。"""
import zipfile
import pathlib

OUT = pathlib.Path(r"C:\workspace\legado-win\test_epub.epub")

CHAPTERS = [
    ("chapter1", "第一章 百世书", "吕阳穿越到修仙世界，成了一名魔门人材。这是第一章的正文。"),
    ("chapter2", "第二章 顺天易", "修仙之路，顺天易，逆天难。这是第二章的正文。"),
    ("chapter3", "第三章 魔门人材", "圣宗处处是人材。这是第三章的正文。"),
]

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

manifest = "\n".join(
    f'    <item id="{cid}" href="{cid}.xhtml" media-type="application/xhtml+xml"/>'
    for cid, _, _ in CHAPTERS
)
spine = "\n".join(f'    <itemref idref="{cid}"/>' for cid, _, _ in CHAPTERS)

OPF = f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"
            xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>EPUB 测试书</dc:title>
    <dc:creator>测试作者</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId">urn:uuid:test-epub-0001</dc:identifier>
    <dc:description>这是一本用于验证 legado Windows 移植的 EPUB 测试书。</dc:description>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
{manifest}
  </manifest>
  <spine toc="ncx">
{spine}
  </spine>
</package>
"""

navpoints = "\n".join(
    f"""    <navPoint id="np{i+1}" playOrder="{i+1}">
      <navLabel><text>{title}</text></navLabel>
      <content src="{cid}.xhtml"/>
    </navPoint>"""
    for i, (cid, title, _) in enumerate(CHAPTERS)
)

NCX = f"""<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:test-epub-0001"/>
    <meta name="dtb:depth" content="1"/>
  </head>
  <docTitle><text>EPUB 测试书</text></docTitle>
  <navMap>
{navpoints}
  </navMap>
</ncx>
"""


def xhtml(title: str, body: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>{title}</title></head>
<body>
  <h1>{title}</h1>
  <p>{body}</p>
  <p>这一段用于验证 EPUB 正文提取与排版。</p>
</body>
</html>
"""


def main():
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        # mimetype 必须是第一个条目且不压缩
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip",
                   compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", CONTAINER)
        z.writestr("OEBPS/content.opf", OPF)
        z.writestr("OEBPS/toc.ncx", NCX)
        for cid, title, body in CHAPTERS:
            z.writestr(f"OEBPS/{cid}.xhtml", xhtml(title, body))
    print(f"已生成 {OUT}（{OUT.stat().st_size} bytes，{len(CHAPTERS)} 章）")


if __name__ == "__main__":
    main()
