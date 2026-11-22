# pdf2odt

Converts PDF interface specifications into ODT files so that two versions can be compared in
LibreOffice Writer with *Edit → Track Changes → Compare Document*. The goal is text that compares
well, not a faithful reproduction of the layout.

## Build and run

Requirements: Java 25, Maven 3.

```bash
mvn package
java -jar target/pdf2odt-0.1.0-SNAPSHOT.jar input.pdf output.odt
```

## What is converted

- **Paragraphs:** A new paragraph starts when the line distance is clearly larger than usual in the
  document (measured separately for body text and table cells) and at every list item. Hyphenation
  at line ends is resolved if the word occurs unhyphenated elsewhere in the document. Characters of
  the Symbol and Wingdings fonts used in lists are translated into real characters (•, ▪, ➢, →).
- **Page headers and footers** are removed: the top or bottom line in the margin zone that starts
  the same way on at least 20 % of the pages (page numbers are ignored).
- **Page breaks:** A paragraph without a sentence end is joined with the start of the next page,
  a table with its continuation (the repeated header row is dropped).
- The table of contents is kept as normal text.
- **Headings:** bold lines that are larger than body text or have multi-level numbering
  (`2.2.1`, `Anlage A.1`). The outline level follows from the numbering
  (`Teil A` → 1, `3` → 2, `3.1` → 3, `Anlage A` → 2, `Anlage A.1` → 3).
  Pages with many dot-leader lines (table of contents) contain no headings.
- **Tables:** are detected by their grid lines, including merged cells. Every table row is written
  as a separate single-row table (with identical column widths, so it still looks like one table):
  otherwise the Writer comparison marks the whole table on any change, this way only the affected
  row. Cells merged across several rows appear only in their first row.
- **Images:** are converted to PNG (including JPEG2000). Below every image there is a line
  `[Image <hash>]` with a perceptual hash. If an image changes in content, this line changes and the
  Writer comparison shows the change. Single-coloured images are omitted.

## Measuring comparison quality

The scripts in `tools/` measure how many words Writer marks compared to the real changes
(requires LibreOffice and `python3-uno`):

```bash
python3 tools/dump.py old.odt > old.txt
python3 tools/dump.py new.odt > new.txt
python3 tools/wordiff.py old.txt new.txt               # real changes (ignoring paragraph boundaries)
python3 tools/compare.py old.odt new.odt result.json   # words marked by Writer
```

The closer the number of words marked by Writer is to the number of real changes, the less noise
the comparison contains. Comparing a file with itself must yield 0 changes.
