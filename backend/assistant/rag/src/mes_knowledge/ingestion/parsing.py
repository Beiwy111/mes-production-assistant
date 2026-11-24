"""Local extraction for private uploads. Locations and heading paths travel with the text."""
import csv
import hashlib
import io
import re
import zipfile
from functools import lru_cache
from pathlib import PurePath

from ..documents import DocumentError, PlainHTML

TYPES = {'.pdf', '.doc', '.docx', '.ppt', '.pptx', '.xls', '.xlsx', '.csv',
         '.txt', '.md', '.markdown', '.html', '.htm', '.png', '.jpg', '.jpeg'}
MAX_BYTES = 10 * 1024 * 1024


def validate_upload(filename, data):
    if (not filename.strip() or len(filename) > 200 or re.search(r'[\\/:\x00-\x1f]', filename)
            or PurePath(filename).suffix.lower() not in TYPES):
        raise DocumentError('请选择支持的文件类型，并使用不含目录的文件名')
    if not 0 < len(data) <= MAX_BYTES:
        raise DocumentError('单个文件应在 1 字节至 10 MiB 之间')


def section(location, text, **metadata):
    return {'location': location, 'text': text.strip(), 'metadata': metadata}


def table_text(rows):
    lines = []
    for row in rows:
        values = [str(cell).strip() if cell is not None else '' for cell in row]
        if any(values):
            lines.append(' | '.join(values))
    return '\n'.join(lines)


@lru_cache(maxsize=1)
def ocr_engine():
    from rapidocr import RapidOCR
    return RapidOCR(params={'EngineConfig.onnxruntime.intra_op_num_threads': 2,
                            'EngineConfig.onnxruntime.inter_op_num_threads': 1,
                            'Global.log_level': 'warning'})


def recognize(image):
    from PIL import ImageOps
    import numpy as np
    raster = ImageOps.exif_transpose(image).convert('RGB')
    result = ocr_engine()(np.asarray(raster)[:, :, ::-1].copy())
    return '\n'.join(result.txts or ())


def image_sections(stream):
    from PIL import Image
    with Image.open(stream) as image:
        if image.width * image.height > 24_000_000:
            raise DocumentError('图片分辨率超过 2400 万像素')
        yield section('image:1', recognize(image), ocr=True, width=image.width,
                      height=image.height, imageFormat=image.format, mode=image.mode)


def pdf_sections(stream):
    import pdfplumber
    from pypdf import PdfReader
    # The PDF content stream preserves word spacing in pages with overlapping journal
    # layouts. pdfplumber supplies table geometry and the raster used for image-only pages.
    native = PdfReader(stream)
    if native.is_encrypted or len(native.pages) > 200:
        raise DocumentError('PDF 已加密或超过 200 页')
    stream.seek(0)
    with pdfplumber.open(stream) as pdf:
        if len(pdf.pages) > 200:
            raise DocumentError('PDF 超过 200 页')
        for number, page in enumerate(pdf.pages, 1):
            location = f'page:{number}'
            try:
                text = native.pages[number - 1].extract_text() or ''
            except Exception:
                text = ''
            if not text.strip():
                text = page.extract_text() or ''
            scanned = not text.strip()
            if scanned:
                # Limit both axes before rasterization, including unusually large page boxes.
                if min(page.width, page.height) <= 0:
                    raise DocumentError('PDF 页面尺寸无效')
                resolution = min(160, 2400 * 72 / max(page.width, page.height))
                text = recognize(page.to_image(resolution=resolution).original)
            yield section(location, text, page=number, ocr=scanned, imageCount=len(page.images))
            for index, rows in enumerate(page.extract_tables(), 1):
                yield section(f'{location}/table:{index}', table_text(rows), page=number, table=index)
            page.close()


def office_archive(stream):
    with zipfile.ZipFile(stream) as archive:
        items = archive.infolist()
        if len(items) > 4000 or sum(i.file_size for i in items) > 64 * 1024 * 1024:
            raise DocumentError('Office 压缩包解压后超过容量限制')
    stream.seek(0)


def docx_sections(stream):
    from docx import Document
    from docx.table import Table
    document = Document(stream)
    headings, lines = {}, []
    for index, item in enumerate(document.iter_inner_content(), 1):
        if isinstance(item, Table):
            if lines:
                yield section(f'paragraph:{index - 1}', '\n'.join(lines), headings=list(headings.values()))
                lines.clear()
            yield section(f'table:{index}', table_text([c.text for c in r.cells] for r in item.rows),
                          headings=list(headings.values()), table=index)
            continue
        heading = re.search(r'(?:Heading|标题)\s*([1-9])', item.style.name or '', re.I)
        if heading:
            if lines:
                yield section(f'paragraph:{index - 1}', '\n'.join(lines), headings=list(headings.values()))
                lines.clear()
            level = int(heading[1])
            headings = {k: v for k, v in headings.items() if k < level}
            headings[level] = item.text
        lines.append(item.text)
    if lines:
        yield section('document:end', '\n'.join(lines), headings=list(headings.values()))


def pptx_sections(stream):
    from pptx import Presentation
    from pptx.enum.shapes import MSO_SHAPE_TYPE
    for number, slide in enumerate(Presentation(stream).slides, 1):
        title = slide.shapes.title
        metadata = {'slide': number, 'headings': [title.text] if title is not None else []}
        shapes = list(slide.shapes)
        lines = []
        for shape in shapes:
            if shape.shape_type == MSO_SHAPE_TYPE.GROUP:
                shapes.extend(shape.shapes)
            if shape.has_text_frame:
                lines.append(shape.text)
            if shape.has_table:
                rows = ([c.text for c in row.cells] for row in shape.table.rows)
                yield section(f'slide:{number}/table:{shape.shape_id}', table_text(rows),
                              table=shape.shape_id, **metadata)
        yield section(f'slide:{number}', '\n'.join(lines), **metadata)


def xlsx_sections(stream):
    from openpyxl import load_workbook
    workbook = load_workbook(stream, read_only=True, data_only=True, keep_links=False)
    try:
        for sheet in workbook:
            # Ignore spurious saved dimensions; enforce limits on actual cells read.
            sheet.reset_dimensions()
            block, total = [], 0
            for index, row in enumerate(sheet.iter_rows(values_only=True), 1):
                if index > 50_000 or len(row) > 2048:
                    raise DocumentError('工作表范围超过 5 万行或 2048 列')
                line = table_text([row])
                total += len(line)
                if total > 500_000:
                    raise DocumentError('工作表提取内容过长')
                block.append(line)
                if len(block) == 80:
                    yield section(f'{sheet.title}!{index-79}:{index}', '\n'.join(block),
                                  sheet=sheet.title, table=sheet.title, rowStart=index-79, rowEnd=index)
                    block.clear()
            if block:
                yield section(f'{sheet.title}!{index-len(block)+1}:{index}', '\n'.join(block),
                              sheet=sheet.title, table=sheet.title, rowStart=index-len(block)+1, rowEnd=index)
    finally:
        workbook.close()


def legacy_sections(data, suffix):
    import kreuzberg
    mime = {'.doc': 'application/msword', '.xls': 'application/vnd.ms-excel',
            '.ppt': 'application/vnd.ms-powerpoint'}[suffix]
    if not data.startswith(bytes.fromhex('D0CF11E0A1B11AE1')):
        raise DocumentError('旧版 Office 文件头无效')
    config = kreuzberg.ExtractionConfig(use_cache=False, disable_ocr=True, extraction_timeout_secs=75,
                                        pages=kreuzberg.PageConfig(extract_pages=True))
    result = kreuzberg.extract_bytes_sync(data, mime, config)
    if result.pages:
        for page in result.pages:
            yield section(f"page:{page['page_number']}", page['content'], page=page['page_number'])
    else:
        yield section('document', result.content)
    for index, table in enumerate(result.tables, 1):
        yield section(f'table:{index}', table_text(table.cells), page=table.page_number, table=index)


def text_sections(data, suffix):
    try:
        text = data.decode('utf-8-sig')
    except UnicodeDecodeError:
        text = data.decode('gb18030')
    if suffix in ('.html', '.htm'):
        html = PlainHTML()
        html.feed(text)
        yield section('document', ''.join(html.parts))
    elif suffix == '.csv':
        yield section('table:1', table_text(csv.reader(io.StringIO(text))), table=1)
    elif suffix in ('.md', '.markdown'):
        headings, lines, start = {}, [], 1
        for index, line in enumerate(text.splitlines(), 1):
            heading = re.match(r'^(#{1,6})\s+(.+)$', line)
            if heading:
                if lines:
                    yield section(f'line:{start}', '\n'.join(lines), headings=list(headings.values()))
                level = len(heading[1])
                headings = {k: v for k, v in headings.items() if k < level}
                headings[level] = heading[2].strip()
                start, lines = index, []
            lines.append(line)
        yield section(f'line:{start}', '\n'.join(lines), headings=list(headings.values()))
    else:
        for page, value in enumerate(text.split('\f'), 1):
            yield section(f'page:{page}', value, page=page)


def extract(filename, data):
    validate_upload(filename, data)
    suffix = PurePath(filename).suffix.lower()
    stream = io.BytesIO(data)
    try:
        if suffix in ('.docx', '.pptx', '.xlsx'):
            office_archive(stream)
            parts = {'.docx': docx_sections, '.pptx': pptx_sections, '.xlsx': xlsx_sections}[suffix](stream)
        elif suffix in ('.doc', '.xls', '.ppt'):
            parts = legacy_sections(data, suffix)
        elif suffix in ('.png', '.jpg', '.jpeg'):
            parts = image_sections(stream)
        elif suffix == '.pdf':
            parts = pdf_sections(stream)
        else:
            parts = text_sections(data, suffix)
        sections, length = [], 0
        for part in parts:
            if part['text']:
                sections.append(part)
                length += len(part['text'])
            if length > 500_000 or len(sections) > 4000:
                raise DocumentError('提取内容超过 50 万字或 4000 个段落组')
        if not sections:
            raise DocumentError('未提取到可识别的文字，请检查原件或图片清晰度')
    except DocumentError:
        raise
    except Exception as exc:
        raise DocumentError(f'无法解析此文件，请检查格式、加密状态或编码（{type(exc).__name__}）') from exc
    metadata = {'format': suffix[1:], 'sha256': hashlib.sha256(data).hexdigest(),
                'sections': len(sections), 'characters': length,
                'tables': len({str(s['metadata'].get('sheet') or s['location']) for s in sections if 'table' in s['metadata']}),
                'ocrSections': sum(bool(s['metadata'].get('ocr')) for s in sections)}
    if suffix in ('.jpg', '.jpeg', '.png'):
        metadata.update(sections[0]['metadata'])
    return sections, metadata
