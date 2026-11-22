package pdf2odt;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import pdf2odt.GraphicsExtractor.Graphics;
import pdf2odt.GraphicsExtractor.PlacedImage;
import pdf2odt.GraphicsExtractor.Segment;
import pdf2odt.Model.Block;
import pdf2odt.Model.Cell;
import pdf2odt.Model.Paragraph;
import pdf2odt.Model.Table;
import pdf2odt.PageBuilder.Context;
import pdf2odt.PageBuilder.Line;
import pdf2odt.PageExtractor.Word;
import pdf2odt.TableDetector.DetectedTable;

/**
 * Assembles the document from its pages: determines document-wide metrics, removes page headers
 * and footers, and joins paragraphs and tables across page breaks.
 */
final class DocumentBuilder {

    /** A page header/footer appears on at least this share of the pages. */
    private static final double HEADER_PAGE_SHARE = 0.2;
    /** Page headers/footers lie within this share of the page height at the top or bottom. */
    private static final float EDGE_ZONE = 0.1f;
    /** Column widths differing by at most this much are considered equal (cm). */
    private static final double SAME_WIDTH_CM = 0.1;

    private DocumentBuilder() {
    }

    static List<Block> convert(PDDocument doc) throws IOException {
        List<List<Word>> pages = PageExtractor.words(doc);
        List<Graphics> graphics = new ArrayList<>();
        List<List<DetectedTable>> tables = new ArrayList<>();
        List<List<Word>> flow = new ArrayList<>();
        List<Float> heights = new ArrayList<>();
        PDFRenderer renderer = new PDFRenderer(doc);
        for (int i = 0; i < pages.size(); i++) {
            PDRectangle box = doc.getPage(i).getCropBox();
            heights.add(box.getHeight());
            Graphics g = withFigures(GraphicsExtractor.extract(doc.getPage(i)), pages.get(i), box, renderer, i);
            List<DetectedTable> pageTables = TableDetector.detect(g.segments(), pages.get(i));
            List<Word> pageFlow = new ArrayList<>(pages.get(i));
            pageTables.forEach(t -> pageFlow.removeAll(t.words()));
            graphics.add(g);
            tables.add(pageTables);
            flow.add(pageFlow);
        }
        removeHeadersAndFooters(flow, heights);

        float bodySize = bodySize(pages);
        Context ctx = new Context(bodySize, pitch(flow, bodySize), vocabulary(pages));

        List<List<Block>> pageBlocks = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            pageBlocks.add(PageBuilder.build(flow.get(i), graphics.get(i).images(), tables.get(i), ctx));
        }
        return merge(pageBlocks, ctx.vocabulary());
    }

    /**
     * Replaces the drawings of a page by rendered images: the lines, images and words inside a drawing
     * are removed (words directly from {@code words}), the drawing is added as one image.
     */
    private static Graphics withFigures(Graphics g, List<Word> words, PDRectangle box, PDFRenderer renderer,
            int pageIndex) throws IOException {
        List<Rectangle2D> wordBoxes = words.stream()
                .<Rectangle2D>map(w -> new Rectangle2D.Double(w.x(), w.top(), w.right() - w.x(), w.baseline() - w.top()))
                .toList();
        List<Rectangle2D> figures = FigureDetector.detect(g.marks(), g.shapes(), wordBoxes, box.getWidth(),
                box.getHeight());
        if (figures.isEmpty()) {
            return g;
        }
        words.removeIf(w -> FigureDetector.inside(figures, (w.x() + w.right()) / 2, (w.top() + w.baseline()) / 2));
        List<Segment> segments = g.segments().stream().filter(s -> !FigureDetector.inside(figures,
                s.horizontal() ? (s.from() + s.to()) / 2 : s.pos(), s.horizontal() ? s.pos() : (s.from() + s.to()) / 2))
                .toList();
        List<PlacedImage> images = new ArrayList<>(g.images().stream()
                .filter(i -> !FigureDetector.inside(figures, i.bounds().getCenterX(), i.bounds().getCenterY()))
                .toList());
        BufferedImage page = FigureDetector.renderPage(renderer, pageIndex);
        for (Rectangle2D figure : figures) {
            images.add(FigureDetector.cut(page, figure));
        }
        return new Graphics(images, segments, g.marks(), g.shapes());
    }

    // ---------------------------------------------------------------- page headers and footers

    /**
     * Removes the top or bottom line of a page if it lies in the margin zone and starts the same way on
     * many pages (digits such as page numbers are ignored). The exact height is ignored because Word may
     * place it differently per section.
     */
    static void removeHeadersAndFooters(List<List<Word>> pages, List<Float> pageHeights) {
        Map<String, Integer> count = new HashMap<>();
        List<Map<String, Line>> candidates = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            Map<String, Line> edge = new HashMap<>();
            List<Line> lines = PageBuilder.lines(pages.get(i));
            float margin = pageHeights.get(i) * EDGE_ZONE;
            if (!lines.isEmpty() && lines.getFirst().top() < margin) {
                edge.put("top|" + start(lines.getFirst()), lines.getFirst());
            }
            if (!lines.isEmpty() && lines.getLast().baseline() > pageHeights.get(i) - margin) {
                edge.put("bottom|" + start(lines.getLast()), lines.getLast());
            }
            candidates.add(edge);
            edge.keySet().forEach(key -> count.merge(key, 1, Integer::sum));
        }
        int threshold = Math.max(3, (int) Math.ceil(pages.size() * HEADER_PAGE_SHARE));
        for (int i = 0; i < pages.size(); i++) {
            for (Map.Entry<String, Line> edge : candidates.get(i).entrySet()) {
                if (count.get(edge.getKey()) >= threshold) {
                    pages.get(i).removeAll(edge.getValue().words());
                }
            }
        }
    }

    /** The first two words, digits replaced. */
    private static String start(Line line) {
        return String.join(" ", line.words().stream().limit(2).map(Word::text).toList()).replaceAll("\\d", "#");
    }

    // ---------------------------------------------------------------- metrics

    /** The font size used by most characters of the document. */
    static float bodySize(List<List<Word>> pages) {
        Map<Float, Integer> chars = new HashMap<>();
        for (List<Word> page : pages) {
            for (Word w : page) {
                chars.merge(Math.round(w.fontSize() * 2) / 2f, w.text().length(), Integer::sum);
            }
        }
        return mode(chars, 10f);
    }

    /** The most frequent baseline distance between consecutive lines in body text size. */
    static float pitch(List<List<Word>> regions, float bodySize) {
        Map<Float, Integer> gaps = new HashMap<>();
        for (List<Word> region : regions) {
            List<Line> lines = PageBuilder.lines(region);
            for (int i = 1; i < lines.size(); i++) {
                Line a = lines.get(i - 1);
                Line b = lines.get(i);
                float gap = b.baseline() - a.baseline();
                if (Math.abs(a.fontSize() - bodySize) < 0.5f && Math.abs(b.fontSize() - bodySize) < 0.5f
                        && gap > 0.8f * bodySize && gap < 3f * bodySize) {
                    gaps.merge(Math.round(gap * 2) / 2f, 1, Integer::sum);
                }
            }
        }
        return mode(gaps, 1.2f * bodySize);
    }

    private static float mode(Map<Float, Integer> histogram, float fallback) {
        return histogram.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallback);
    }

    private static Set<String> vocabulary(List<List<Word>> pages) {
        Set<String> words = new HashSet<>();
        for (List<Word> page : pages) {
            for (Word w : page) {
                for (String part : w.text().split("[^\\p{L}]+")) {
                    if (!part.isEmpty()) {
                        words.add(part.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return words;
    }

    // ---------------------------------------------------------------- page transitions

    /**
     * Concatenates the pages. A paragraph without a sentence end is joined with the first paragraph of the
     * next page, a table with an identically structured table at the start of the next page (dropping its
     * repeated header row and joining a row that was split by the page break).
     */
    static List<Block> merge(List<List<Block>> pages, Set<String> vocabulary) {
        List<Block> result = new ArrayList<>();
        for (List<Block> page : pages) {
            List<Block> rest = page;
            if (!result.isEmpty() && !page.isEmpty()) {
                Block merged = mergeBlocks(result.getLast(), page.getFirst(), vocabulary);
                if (merged != null) {
                    result.set(result.size() - 1, merged);
                    rest = page.subList(1, page.size());
                }
            }
            result.addAll(rest);
        }
        return result;
    }

    /** The joined block, or {@code null} if the blocks do not belong together. */
    private static Block mergeBlocks(Block last, Block first, Set<String> vocabulary) {
        if (last instanceof Paragraph a && first instanceof Paragraph b
                && !endsSentence(a.text()) && !b.text().matches("^[•\\-–] .*")) {
            return new Paragraph(PageBuilder.joinText(a.text(), b.text(), vocabulary));
        }
        if (last instanceof Table a && first instanceof Table b && sameColumns(a, b)) {
            List<List<Cell>> rows = new ArrayList<>(a.rows());
            List<List<Cell>> continuation = b.rows();
            if (!continuation.isEmpty() && continuation.getFirst().equals(a.rows().getFirst())) {
                continuation = continuation.subList(1, continuation.size());
            }
            if (!continuation.isEmpty() && continuesRow(rows.getLast(), continuation.getFirst())) {
                rows.set(rows.size() - 1, joinRows(rows.getLast(), continuation.getFirst(), vocabulary));
                continuation = continuation.subList(1, continuation.size());
            }
            rows.addAll(continuation);
            return new Table(rows, a.columnWidthsCm());
        }
        return null;
    }

    /**
     * Is {@code next} the rest of {@code last}, split by a page break? Its first (key) cell is empty while
     * that of {@code last} is not, both have the same cell structure, and at least one cell of {@code last}
     * that continues in {@code next} ends without a sentence end.
     */
    private static boolean continuesRow(List<Cell> last, List<Cell> next) {
        if (last.size() != next.size() || last.getFirst() == null || next.getFirst() == null
                || last.getFirst().text().isEmpty() || !next.getFirst().text().isEmpty()) {
            return false;
        }
        boolean brokenSentence = false;
        for (int i = 0; i < last.size(); i++) {
            if ((last.get(i) == null) != (next.get(i) == null)) {
                return false;
            }
            if (next.get(i) != null && !next.get(i).text().isEmpty() && !endsSentence(last.get(i).text())) {
                brokenSentence = true;
            }
        }
        return brokenSentence;
    }

    /** Joins the cell texts of a row split by a page break; spans are taken from the first part. */
    private static List<Cell> joinRows(List<Cell> last, List<Cell> next, Set<String> vocabulary) {
        List<Cell> row = new ArrayList<>();
        for (int i = 0; i < last.size(); i++) {
            Cell a = last.get(i);
            Cell b = next.get(i);
            if (a == null) {
                row.add(null);
            } else if (b.text().isEmpty() || a.text().isEmpty()) {
                row.add(new Cell(a.text() + b.text(), a.colSpan(), a.rowSpan()));
            } else {
                row.add(new Cell(PageBuilder.joinText(a.text(), b.text(), vocabulary), a.colSpan(), a.rowSpan()));
            }
        }
        return row;
    }

    private static boolean endsSentence(String text) {
        return text.matches("(?s).*[.:;!?]$");
    }

    private static boolean sameColumns(Table a, Table b) {
        if (a.columnWidthsCm().size() != b.columnWidthsCm().size()) {
            return false;
        }
        for (int i = 0; i < a.columnWidthsCm().size(); i++) {
            if (Math.abs(a.columnWidthsCm().get(i) - b.columnWidthsCm().get(i)) > SAME_WIDTH_CM) {
                return false;
            }
        }
        return true;
    }
}
