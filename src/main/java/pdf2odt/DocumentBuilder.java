package pdf2odt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;

import pdf2odt.GraphicsExtractor.Graphics;
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
        for (int i = 0; i < pages.size(); i++) {
            heights.add(doc.getPage(i).getCropBox().getHeight());
            Graphics g = GraphicsExtractor.extract(doc.getPage(i));
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
     * repeated header row).
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
                && !a.text().matches("(?s).*[.:;!?]$") && !b.text().matches("^[•\\-–] .*")) {
            return new Paragraph(PageBuilder.joinText(a.text(), b.text(), vocabulary));
        }
        if (last instanceof Table a && first instanceof Table b && sameColumns(a, b)) {
            List<List<Cell>> rows = new ArrayList<>(a.rows());
            List<List<Cell>> continuation = b.rows();
            if (!continuation.isEmpty() && continuation.getFirst().equals(a.rows().getFirst())) {
                continuation = continuation.subList(1, continuation.size());
            }
            rows.addAll(continuation);
            return new Table(rows, a.columnWidthsCm());
        }
        return null;
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
