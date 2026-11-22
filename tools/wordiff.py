"""Word-level comparison of two text dumps (tools/dump.py), ignoring paragraph and line boundaries.
The number of differing words approximates the real content changes; if Writer (tools/compare.py)
marks considerably more, the remaining marks are noise.

Usage:  python3 tools/wordiff.py old.txt new.txt
"""
import difflib
import sys


def words(path):
    result = []
    for line in open(path):
        line = line.strip().lstrip('#|').replace(' | ', ' ').replace(' / ', ' ')
        result += line.split()
    return result


old, new = words(sys.argv[1]), words(sys.argv[2])
deleted = inserted = 0
for op, i1, i2, j1, j2 in difflib.SequenceMatcher(None, old, new, autojunk=False).get_opcodes():
    if op != 'equal':
        deleted += i2 - i1
        inserted += j2 - j1
print(f'words old {len(old)}, new {len(new)}: {deleted} deleted, {inserted} inserted '
      f'= {deleted + inserted} changed words')
