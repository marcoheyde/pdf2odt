package pdf2odt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import pdf2odt.GraphicsExtractor.Segment;
import pdf2odt.Model.Cell;
import pdf2odt.Model.Table;
import pdf2odt.PageExtractor.Word;
import pdf2odt.TableDetector.DetectedTable;
import pdf2odt.TableDetector.WordCell;

class TableDetectorTest {

    @Test
    void simpleGrid() {
        // 2 columns (x 0–100–200), 2 rows (y 0–20–40)
        List<Segment> segments = grid(new float[] {0, 100, 200}, new float[] {0, 20, 40});
        List<Word> words = List.of(word("A", 10, 15), word("B", 110, 15), word("C", 10, 35), word("D", 110, 35));

        List<DetectedTable> tables = TableDetector.detect(segments, words);

        assertEquals(1, tables.size());
        assertEquals(List.of(List.of("A", "B"), List.of("C", "D")), texts(tables.getFirst()));
        assertEquals(List.of(100f, 100f), tables.getFirst().columnWidths());
        assertEquals(4, tables.getFirst().words().size());
    }

    @Test
    void cellWithoutSeparatorIsMerged() {
        List<Segment> segments = new ArrayList<>();
        for (float y : new float[] {0, 20, 40}) {
            segments.add(new Segment(true, y, 0, 200));
        }
        segments.add(new Segment(false, 0, 0, 40));
        segments.add(new Segment(false, 200, 0, 40));
        segments.add(new Segment(false, 100, 0, 20)); // separator only in the first row

        DetectedTable table = TableDetector.detect(segments, List.of(word("über", 50, 35))).getFirst();

        WordCell merged = table.rows().get(1).getFirst();
        assertEquals(2, merged.colSpan());
        assertEquals(List.of(List.of("", ""), List.of("über", "")), texts(table));
        assertNull(table.rows().get(1).get(1));
    }

    @Test
    void singleLinesAreNoTable() {
        List<Segment> segments = List.of(new Segment(true, 100, 0, 500), new Segment(true, 200, 0, 500));
        assertTrue(TableDetector.detect(segments, List.of()).isEmpty());
    }

    /** A PDF whose table lines are drawn as thin filled rectangles. */
    @Test
    void tableFromFilledRectangles() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(0.75f);
                for (float y : new float[] {700, 680, 660}) {
                    cs.addRect(100, y, 300, 0.5f);
                }
                for (float x : new float[] {100, 250, 400}) {
                    cs.addRect(x, 660, 0.5f, 40.5f);
                }
                cs.fill();
                cs.setNonStrokingColor(0f);
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
                text(cs, 105, 686, "Feld");
                text(cs, 255, 686, "Typ");
                text(cs, 105, 666, "LIID");
                text(cs, 255, 666, "String");
                cs.endText();
            }

            List<Model.Block> blocks = DocumentBuilder.convert(doc);

            assertEquals(1, blocks.size());
            Table table = (Table) blocks.getFirst();
            assertEquals(List.of(List.of("Feld"), List.of("Typ")), table.rows().get(0).stream().map(Cell::paragraphs).toList());
            assertEquals(List.of(List.of("LIID"), List.of("String")), table.rows().get(1).stream().map(Cell::paragraphs).toList());
        }
    }

    private static void text(PDPageContentStream cs, float x, float y, String text) throws Exception {
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.newLineAtOffset(-x, -y);
    }

    private static List<Segment> grid(float[] xs, float[] ys) {
        List<Segment> segments = new ArrayList<>();
        for (float y : ys) {
            segments.add(new Segment(true, y, xs[0], xs[xs.length - 1]));
        }
        for (float x : xs) {
            segments.add(new Segment(false, x, ys[0], ys[ys.length - 1]));
        }
        return segments;
    }

    private static Word word(String text, float x, float baseline) {
        return new Word(text, x, x + 20, baseline - 8, baseline, 9, false);
    }

    private static List<List<String>> texts(DetectedTable table) {
        return table.rows().stream()
                .map(row -> row.stream().map(c -> c == null ? "" : String.join(" ",
                        c.words().stream().map(Word::text).toList())).toList())
                .toList();
    }
}
