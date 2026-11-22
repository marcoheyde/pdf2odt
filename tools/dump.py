"""Prints the content of an ODT file as text: headings with '#', table rows with '|'.

Usage:  python3 tools/dump.py file.odt > file.txt
"""
import sys
import zipfile
import xml.etree.ElementTree as ET

TEXT = '{urn:oasis:names:tc:opendocument:xmlns:text:1.0}'
TABLE = '{urn:oasis:names:tc:opendocument:xmlns:table:1.0}'
OFFICE = '{urn:oasis:names:tc:opendocument:xmlns:office:1.0}'


def text(element):
    return ''.join(element.itertext())


root = ET.fromstring(zipfile.ZipFile(sys.argv[1]).read('content.xml'))
for e in root.find(f'.//{OFFICE}text'):
    if e.tag == TEXT + 'h':
        print('#' * int(e.get(TEXT + 'outline-level')), text(e))
    elif e.tag == TEXT + 'p':
        print(text(e))
    elif e.tag == TABLE + 'table':
        for row in e.findall(TABLE + 'table-row'):
            cells = [' / '.join(text(p) for p in c) for c in row if c.tag == TABLE + 'table-cell']
            print('  | ' + ' | '.join(cells))
