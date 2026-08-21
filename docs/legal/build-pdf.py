# -*- coding: utf-8 -*-
"""
docs/legal/*.md dosyalarini PDF'e cevirir.

Kullanim:
    python docs/legal/build-pdf.py

NEDEN BU SCRIPT VAR
-------------------
Belgeler Markdown'da tutuluyor cunku duzenlenmesi ve surum kontrolunde
takip edilmesi kolay. PDF ise Play Console'a ek olarak ve kullaniciya
gondermek icin gerekiyor. Ikisini elle senkron tutmak, birinin gunun
birinde eskimesi demektir -- bu script tek kaynaktan uretir.

NOT: Google Play, gizlilik politikasi icin PUBLIC BIR URL ister.
PDF tek basina yeterli DEGILDIR; yayinlanmis bir web sayfasi gerekir
(bkz. docs/legal/README.md).
"""
import io
import os
import re
import sys

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    HRFlowable,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

HERE = os.path.dirname(os.path.abspath(__file__))

# Turkce karakterler (s, g, i, o, u ve buyukleri) reportlab'in yerlesik
# Helvetica'sinda YOKTUR -- Latin-1 disindalar. Unicode bir TTF sart,
# yoksa bu harfler karecik olarak basilir.
FONT_CANDIDATES = [
    (r'C:\Windows\Fonts\segoeui.ttf', r'C:\Windows\Fonts\segoeuib.ttf',
     r'C:\Windows\Fonts\segoeuii.ttf'),
    (r'C:\Windows\Fonts\arial.ttf', r'C:\Windows\Fonts\arialbd.ttf',
     r'C:\Windows\Fonts\ariali.ttf'),
    (r'C:\Windows\Fonts\calibri.ttf', r'C:\Windows\Fonts\calibrib.ttf',
     r'C:\Windows\Fonts\calibrii.ttf'),
]

BODY_FONT = 'DocBody'
BOLD_FONT = 'DocBold'
ITALIC_FONT = 'DocItalic'

INK = colors.HexColor('#14202B')
MUTED = colors.HexColor('#5A6B78')
ACCENT = colors.HexColor('#0B7C88')
RULE = colors.HexColor('#D3DEE5')
TABLE_HEAD_BG = colors.HexColor('#EEF4F7')


def register_fonts():
    for regular, bold, italic in FONT_CANDIDATES:
        if os.path.exists(regular) and os.path.exists(bold):
            pdfmetrics.registerFont(TTFont(BODY_FONT, regular))
            pdfmetrics.registerFont(TTFont(BOLD_FONT, bold))
            pdfmetrics.registerFont(
                TTFont(ITALIC_FONT, italic if os.path.exists(italic) else regular)
            )
            return os.path.basename(regular)
    raise SystemExit(
        'Unicode font bulunamadi. Turkce karakterler dogru basilamaz; '
        'islem durduruldu (karecikli bir PDF uretmektense hata vermek dogru).'
    )


def build_styles():
    sheet = getSampleStyleSheet()
    return {
        'title': ParagraphStyle(
            'DocTitle', parent=sheet['Title'], fontName=BOLD_FONT,
            fontSize=22, leading=27, textColor=INK, spaceAfter=2 * mm,
            alignment=TA_LEFT,
        ),
        'h2': ParagraphStyle(
            'DocH2', parent=sheet['Heading2'], fontName=BOLD_FONT,
            fontSize=13.5, leading=17, textColor=INK,
            spaceBefore=6 * mm, spaceAfter=2 * mm,
        ),
        'h3': ParagraphStyle(
            'DocH3', parent=sheet['Heading3'], fontName=BOLD_FONT,
            fontSize=11, leading=14, textColor=ACCENT,
            spaceBefore=4 * mm, spaceAfter=1.5 * mm,
        ),
        'body': ParagraphStyle(
            'DocBodyStyle', parent=sheet['BodyText'], fontName=BODY_FONT,
            fontSize=9.6, leading=14.2, textColor=INK, spaceAfter=2.4 * mm,
        ),
        'bullet': ParagraphStyle(
            'DocBullet', parent=sheet['BodyText'], fontName=BODY_FONT,
            fontSize=9.6, leading=14.2, textColor=INK,
            leftIndent=6 * mm, bulletIndent=2 * mm, spaceAfter=1.2 * mm,
        ),
        'cell': ParagraphStyle(
            'DocCell', fontName=BODY_FONT, fontSize=8.8, leading=12,
            textColor=INK,
        ),
        'cellhead': ParagraphStyle(
            'DocCellHead', fontName=BOLD_FONT, fontSize=8.8, leading=12,
            textColor=INK,
        ),
        'meta': ParagraphStyle(
            'DocMeta', fontName=BODY_FONT, fontSize=8.6, leading=12,
            textColor=MUTED, spaceAfter=1 * mm,
        ),
    }


INLINE_PATTERNS = [
    # Baglantilar: [metin](url) -> tiklanabilir
    (re.compile(r'\[([^\]]+)\]\(([^)]+)\)'), r'<link href="\2" color="#0B7C88">\1</link>'),
    (re.compile(r'\*\*([^*]+)\*\*'), r'<font name="%s">\1</font>' % BOLD_FONT),
    (re.compile(r'`([^`]+)`'), r'<font face="Courier" size="8.6">\1</font>'),
]


def inline(text):
    """Markdown satir ici bicimlerini reportlab isaretlemesine cevirir."""
    text = text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    for pattern, replacement in INLINE_PATTERNS:
        text = pattern.sub(replacement, text)
    return text


def parse_table(lines, index, styles):
    """Markdown tablosunu Table nesnesine cevirir."""
    rows = []
    while index < len(lines) and lines[index].strip().startswith('|'):
        raw = lines[index].strip().strip('|')
        # Ayirici satiri (---|---) atla
        if not re.fullmatch(r'[\s|:-]+', raw):
            rows.append([c.strip() for c in raw.split('|')])
        index += 1

    if not rows:
        return None, index

    data = [
        [Paragraph(inline(c), styles['cellhead' if r == 0 else 'cell']) for c in row]
        for r, row in enumerate(rows)
    ]

    table = Table(data, hAlign='LEFT', repeatRows=1)
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), TABLE_HEAD_BG),
        ('GRID', (0, 0), (-1, -1), 0.4, RULE),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 4),
        ('RIGHTPADDING', (0, 0), (-1, -1), 4),
        ('TOPPADDING', (0, 0), (-1, -1), 3.5),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 3.5),
    ]))
    return table, index


def markdown_to_flowables(text, styles):
    flow = []
    lines = text.split('\n')
    i = 0

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        if stripped.startswith('|'):
            table, i = parse_table(lines, i, styles)
            if table is not None:
                flow.append(Spacer(1, 1.5 * mm))
                flow.append(table)
                flow.append(Spacer(1, 3 * mm))
            continue

        if stripped == '---':
            flow.append(Spacer(1, 2 * mm))
            flow.append(HRFlowable(width='100%', thickness=0.5, color=RULE))
            flow.append(Spacer(1, 2 * mm))
        elif stripped.startswith('### '):
            flow.append(Paragraph(inline(stripped[4:]), styles['h3']))
        elif stripped.startswith('## '):
            flow.append(Paragraph(inline(stripped[3:]), styles['h2']))
        elif stripped.startswith('# '):
            flow.append(Paragraph(inline(stripped[2:]), styles['title']))
        elif stripped.startswith('- '):
            flow.append(Paragraph(inline(stripped[2:]), styles['bullet'], bulletText='•'))
        elif stripped.startswith('**') and stripped.endswith('**') and stripped.count('**') == 2:
            flow.append(Paragraph(inline(stripped), styles['meta']))
        else:
            flow.append(Paragraph(inline(stripped), styles['body']))

        i += 1

    return flow


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont(BODY_FONT, 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawString(20 * mm, 12 * mm, 'EVA AI')
    canvas.drawRightString(A4[0] - 20 * mm, 12 * mm, 'Sayfa %d' % doc.page)
    canvas.setStrokeColor(RULE)
    canvas.setLineWidth(0.4)
    canvas.line(20 * mm, 16 * mm, A4[0] - 20 * mm, 16 * mm)
    canvas.restoreState()


def build(md_path, pdf_path, styles):
    text = io.open(md_path, encoding='utf-8').read()
    doc = SimpleDocTemplate(
        pdf_path, pagesize=A4,
        leftMargin=20 * mm, rightMargin=20 * mm,
        topMargin=18 * mm, bottomMargin=22 * mm,
        title=os.path.basename(md_path).replace('.md', ''),
        author='EVA AI',
    )
    doc.build(markdown_to_flowables(text, styles), onFirstPage=footer, onLaterPages=footer)
    return os.path.getsize(pdf_path)


def main():
    font = register_fonts()
    styles = build_styles()
    print('font:', font)

    sources = sorted(f for f in os.listdir(HERE) if f.endswith('.md'))
    if not sources:
        sys.exit('docs/legal altinda .md dosyasi yok.')

    for name in sources:
        md_path = os.path.join(HERE, name)
        pdf_path = os.path.join(HERE, name[:-3] + '.pdf')
        size = build(md_path, pdf_path, styles)
        print('%-28s -> %-28s %6.1f KB' % (name, os.path.basename(pdf_path), size / 1024))


if __name__ == '__main__':
    main()
