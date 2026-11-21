import io
import zipfile
import pytest
from mes_knowledge.documents import parse_document, DocumentError


def test_text_csv_and_html_preserve_content_without_executing_markup():
    assert 'LCD' in parse_document('spec.md', b'# LCD\nDetails')['sections'][0]['text']
    csv = parse_document('bom.csv', '物料,数量\nPCB,2\n'.encode())
    assert 'PCB' in csv['sections'][0]['text']
    html = parse_document('sop.html', b'<h1>SOP</h1><script>secret()</script><p>inspect LCD</p>')
    assert 'secret' not in str(html) and 'inspect LCD' in str(html)
    assert html['review_status'] == 'DRAFT' and html['persisted'] is False


def test_docx_parses_table_text_and_rejects_zip_expansion():
    content = io.BytesIO()
    with zipfile.ZipFile(content, 'w') as z:
        z.writestr('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p><w:r><w:t>LCD SOP</w:t></w:r></w:p></w:document>')
    assert 'LCD SOP' in str(parse_document('manual.docx', content.getvalue()))
    with pytest.raises(DocumentError): parse_document('../escape.txt', b'text')
    with pytest.raises(DocumentError): parse_document('file.exe', b'text')
    with pytest.raises(DocumentError): parse_document('empty.pdf', b'not-a-pdf')


def test_scanned_pdf_requires_ocr_and_spreadsheet_formulas_are_not_evaluated():
    from pypdf import PdfWriter
    writer=PdfWriter(); writer.add_blank_page(width=200, height=200)
    output=io.BytesIO(); writer.write(output)
    with pytest.raises(DocumentError, match='OCR'):
        parse_document('scan.pdf', output.getvalue())
    output=io.BytesIO()
    with zipfile.ZipFile(output, 'w') as z:
        z.writestr('xl/worksheets/sheet1.xml', '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row><c r="A1"><f>1+1</f></c></row></sheetData></worksheet>')
    result=parse_document('sheet.xlsx', output.getvalue())
    assert 'formula result unavailable' in str(result['sections'])
    assert result['warnings']
