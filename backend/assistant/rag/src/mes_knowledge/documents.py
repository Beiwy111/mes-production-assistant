"""Bounded, non-persistent document extraction. Parsing never approves or publishes knowledge."""
import csv
import hashlib
import io
import re
import zipfile
from pathlib import PurePosixPath
from html.parser import HTMLParser
import xml.etree.ElementTree as ET


class DocumentError(ValueError):
    pass


class PlainHTML(HTMLParser):
    def __init__(self):
        super().__init__(); self.parts = []; self.hidden = 0
    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'): self.hidden += 1
        if tag in ('p', 'div', 'tr', 'br', 'h1', 'h2', 'h3'): self.parts.append('\n')
    def handle_endtag(self, tag):
        if tag in ('script', 'style'): self.hidden = max(0, self.hidden-1)
    def handle_data(self, data):
        if not self.hidden: self.parts.append(data)


def xml(data):
    if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
        raise DocumentError('XML entities are not permitted')
    return ET.fromstring(data)


def parse_document(filename: str, content: bytes):
    if not isinstance(filename, str) or not filename or len(filename)>200 or '/' in filename or '\\' in filename or '\x00' in filename:
        raise DocumentError('A plain filename is required')
    if not content or len(content)>4*1024*1024:
        raise DocumentError('Document must be between 1 byte and 4 MiB')
    suffix = PurePosixPath(filename).suffix.lower()
    sections, warnings = [], []
    try:
        if suffix in ('.txt', '.md', '.csv', '.html', '.htm'):
            text = content.decode('utf-8-sig')
            if suffix == '.csv':
                text = '\n'.join(' | '.join(row) for row in csv.reader(io.StringIO(text)))
            elif suffix in ('.html', '.htm'):
                parser = PlainHTML(); parser.feed(text); text = ''.join(parser.parts)
            sections.append({'location': 'document', 'text': text.strip()})
        elif suffix in ('.docx', '.xlsx'):
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                if len(archive.infolist())>2000 or sum(item.file_size for item in archive.infolist())>20*1024*1024:
                    raise DocumentError('Expanded Office archive exceeds budget')
                if suffix == '.docx':
                    root = xml(archive.read('word/document.xml'))
                    paragraphs = [''.join(n.text or '' for n in paragraph.iter() if n.tag.endswith('}t'))
                                  for paragraph in root.iter() if paragraph.tag.endswith('}p')]
                    sections.append({'location':'document', 'text':'\n'.join(paragraphs)})
                else:
                    strings = []
                    if 'xl/sharedStrings.xml' in archive.namelist():
                        strings = [''.join(n.text or '' for n in item.iter() if n.tag.endswith('}t'))
                                   for item in xml(archive.read('xl/sharedStrings.xml'))]
                    for name in sorted(archive.namelist()):
                        if not re.fullmatch(r'xl/worksheets/sheet\d+\.xml', name): continue
                        cells=[]
                        for node in xml(archive.read(name)).iter():
                            if not node.tag.endswith('}c'): continue
                            values = [n.text or '' for n in node if n.tag.endswith('}v')]
                            value = values[0] if values else ''.join(n.text or '' for n in node.iter() if n.tag.endswith('}t'))
                            if node.get('t') == 's': value = strings[int(value)]
                            if any(n.tag.endswith('}f') for n in node):
                                warnings.append('Formula values are cached; formulas were not calculated.')
                                if not values: value = '[formula result unavailable]'
                            cells.append(node.get('r', '?')+': '+value)
                        sections.append({'location':name, 'text':'\n'.join(cells)})
        elif suffix == '.pdf':
            try:
                from pypdf import PdfReader
            except ImportError:
                raise DocumentError('PDF parser is not installed; OCR is not configured') from None
            reader = PdfReader(io.BytesIO(content))
            if reader.is_encrypted or len(reader.pages)>200: raise DocumentError('Encrypted or oversized PDF is not supported')
            for index, page in enumerate(reader.pages, 1):
                text = page.extract_text() or ''
                if not text.strip(): raise DocumentError(f'PDF page {index} has no text; OCR is required and is not configured')
                sections.append({'location':f'page:{index}', 'text':text})
        else:
            raise DocumentError('Unsupported document type')
        if not sections or not any(s['text'].strip() for s in sections): raise DocumentError('No readable text was extracted')
        if sum(len(s['text']) for s in sections)>500000: raise DocumentError('Extracted text exceeds review budget')
    except DocumentError:
        raise
    except Exception:
        raise DocumentError('Document is invalid or uses an unsupported encoding') from None
    return {'filename':filename, 'version':hashlib.sha256(content).hexdigest(), 'sections':sections,
            'review_status':'DRAFT', 'persisted':False, 'warnings':list(dict.fromkeys(warnings))}
