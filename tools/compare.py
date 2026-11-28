"""Compares two ODT files with LibreOffice like "Edit > Track Changes > Compare Document"
and writes all marked changes as JSON. Optionally the compared document with all marked changes is
saved as PDF (e.g. for screenshots).

Usage:  python3 tools/compare.py old.odt new.odt result.json [compared.pdf]
Requires LibreOffice and the Python module uno (package python3-uno).
"""
import json
import os
import subprocess
import sys
import tempfile
import time

import uno
from com.sun.star.beans import PropertyValue

PORT = 2099


def prop(name, value):
    p = PropertyValue()
    p.Name, p.Value = name, value
    return p


def connect(profile):
    proc = subprocess.Popen(['soffice', '--headless', '--invisible', '--norestore',
                             '-env:UserInstallation=' + uno.systemPathToFileUrl(profile),
                             f'--accept=socket,host=localhost,port={PORT};urp;'])
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext('com.sun.star.bridge.UnoUrlResolver', local)
    for _ in range(60):
        try:
            return proc, resolver.resolve(f'uno:socket,host=localhost,port={PORT};urp;StarOffice.ComponentContext')
        except Exception:
            time.sleep(1)
    proc.kill()
    sys.exit('LibreOffice does not respond')


def redline_text(redline):
    """Text of a change; for whole tables the cell contents, separated by ' | '."""
    start = redline.getPropertyValue('RedlineStart')
    if hasattr(start, 'getCellNames'):
        return ' | '.join(start.getCellByName(n).getString() for n in start.getCellNames()), start.getName()
    end = redline.getPropertyValue('RedlineEnd')
    try:
        cursor = start.getText().createTextCursorByRange(start)
        cursor.gotoRange(end, True)
        return cursor.getString(), None
    except Exception:
        return None, None


def main():
    old, new, out = (os.path.abspath(a) for a in sys.argv[1:4])
    pdf = os.path.abspath(sys.argv[4]) if len(sys.argv) > 4 else None
    with tempfile.TemporaryDirectory() as profile:
        proc, ctx = connect(profile)
        smgr = ctx.ServiceManager
        desktop = smgr.createInstanceWithContext('com.sun.star.frame.Desktop', ctx)
        doc = desktop.loadComponentFromURL(uno.systemPathToFileUrl(new), '_blank', 0, (prop('Hidden', True),))
        if doc is None:
            desktop.terminate()
            proc.wait(timeout=60)
            sys.exit(f'Cannot open {new} (is it open in another LibreOffice window?)')
        dispatcher = smgr.createInstanceWithContext('com.sun.star.frame.DispatchHelper', ctx)
        dispatcher.executeDispatch(doc.getCurrentController().getFrame(), '.uno:CompareDocuments', '', 0,
                                   (prop('URL', uno.systemPathToFileUrl(old)), prop('NoAcceptDialog', True)))
        result = []
        redlines = doc.getRedlines().createEnumeration()
        while redlines.hasMoreElements():
            r = redlines.nextElement()
            text, table = redline_text(r)
            result.append({'type': r.getPropertyValue('RedlineType'), 'text': text, 'table': table})
        if pdf:
            doc.storeToURL(uno.systemPathToFileUrl(pdf), (prop('FilterName', 'writer_pdf_Export'),))
        doc.close(True)
        try:
            desktop.terminate()
        except Exception:
            pass
        proc.wait(timeout=60)
    with open(out, 'w') as f:
        json.dump(result, f, ensure_ascii=False, indent=0)
    words = sum(len((r['text'] or '').replace(' | ', ' ').split()) for r in result)
    print(f'{len(result)} changes, {words} marked words')


if __name__ == '__main__':
    main()
