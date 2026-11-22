package pdf2odt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pdf2odt.GraphicsExtractor.PlacedImage;
import pdf2odt.Model.Block;
import pdf2odt.Model.Cell;
import pdf2odt.Model.Heading;
import pdf2odt.Model.Paragraph;
import pdf2odt.Model.Table;
import pdf2odt.PageExtractor.Word;
import pdf2odt.TableDetector.DetectedTable;
import pdf2odt.TableDetector.WordCell;

/** Builds headings, paragraphs, tables and images from the words, images and tables of one page. */
final class PageBuilder {

    /** Words whose baselines are at most this far apart form one line. */
    private static final float SAME_LINE = 2f;
    /** A baseline distance above the usual line pitch × this factor starts a new paragraph. */
    private static final float PARAGRAPH_GAP = 1.3f;
    /** Headings are bold and larger than body text by at least this factor. */
    private static final float HEADING_SIZE = 1.1f;
    /** A page with at least this many dot-leader lines is a table of contents page. */
    private static final int TOC_LINES = 5;
    private static final double PT_TO_CM = 2.54 / 72;

    /** "Teil A", "Anlage A.1.2", or "3.1.2" / "3." at the start of a line. */
    private static final Pattern NUMBERING =
            Pattern.compile("^(?:(Teil) [A-Z]\\b|Anlage [A-Z]((?:\\.\\d+)*)\\b|(\\d+(?:\\.\\d+)*)\\.?(?:\\s|$))");

    /** Multi-level numbering such as "2.2.1" or "Anlage A.1"; simple list numbers such as "1." do not count. */
    private static final Pattern DEEP_NUMBERING = Pattern.compile("^(?:\\d+(?:\\.\\d+)+|Anlage [A-Z](?:\\.\\d+)+)\\s");

    /** List item marker at the start of a line: "•", "-", "–", "a)" or "1.". */
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[•\\-–]|[a-z]\\)|\\d+\\.)\\s");

    /**
     * Document-wide metrics.
     *
     * @param bodySize   font size of the body text
     * @param flowPitch  usual line pitch in the text flow
     * @param vocabulary all words of the document (lower case), used to resolve hyphenation
     */
    record Context(float bodySize, float flowPitch, Set<String> vocabulary) {
    }

    record Line(List<Word> words, float top, float baseline, float fontSize, boolean bold) {
        String text() {
            StringBuilder sb = new StringBuilder();
            for (Word w : words) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(w.text());
            }
            return sb.toString();
        }
    }

    /** An image or table with its top edge, to be placed between the text lines. */
    private record Placed(float top, Block block) {
    }

    private PageBuilder() {
    }

    /** @param words the words outside of tables, without page headers and footers */
    static List<Block> build(List<Word> words, List<PlacedImage> images, List<DetectedTable> tables, Context ctx) {
        List<Placed> pending = new ArrayList<>();
        images.forEach(i -> pending.add(new Placed(i.top(), i.image())));
        tables.forEach(t -> pending.add(new Placed(t.top(), toTable(t, ctx))));
        pending.sort(Comparator.comparingDouble(Placed::top));

        List<Line> lines = lines(words);
        // Table of contents pages (many dot-leader lines) contain no headings.
        boolean tocPage = lines.stream().filter(l -> l.text().contains("....")).count() >= TOC_LINES;

        List<Block> blocks = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        boolean currentIsHeading = false;
        for (Line line : lines) {
            if (!pending.isEmpty() && pending.getFirst().top() < line.top()) {
                flush(current, currentIsHeading, blocks, ctx);
                while (!pending.isEmpty() && pending.getFirst().top() < line.top()) {
                    blocks.add(pending.removeFirst().block());
                }
            }
            boolean heading = !tocPage && isHeading(line, ctx.bodySize());
            if (!current.isEmpty() && !continues(current.getLast(), line, currentIsHeading, heading, ctx)) {
                flush(current, currentIsHeading, blocks, ctx);
            }
            current.add(line);
            currentIsHeading = heading;
        }
        flush(current, currentIsHeading, blocks, ctx);
        pending.forEach(p -> blocks.add(p.block()));
        return blocks;
    }

    private static Table toTable(DetectedTable detected, Context ctx) {
        List<List<Cell>> rows = new ArrayList<>();
        for (List<WordCell> detectedRow : detected.rows()) {
            List<Cell> row = new ArrayList<>();
            for (WordCell c : detectedRow) {
                row.add(c == null ? null : new Cell(join(lines(c.words()), ctx.vocabulary()), c.colSpan(), c.rowSpan()));
            }
            rows.add(row);
        }
        List<Double> widths = detected.columnWidths().stream().map(w -> w * PT_TO_CM).toList();
        return new Table(rows, widths);
    }

    /** Bold and either larger than body text or numbered with several levels; must contain letters. */
    private static boolean isHeading(Line line, float bodySize) {
        String text = line.text();
        return line.bold()
                && (line.fontSize() > bodySize * HEADING_SIZE || DEEP_NUMBERING.matcher(text).find())
                && text.codePoints().anyMatch(Character::isLetter);
    }

    /** Does {@code line} still belong to the same paragraph or (multi-line) heading? */
    private static boolean continues(Line previous, Line line, boolean previousIsHeading, boolean heading,
            Context ctx) {
        if (previousIsHeading != heading || newParagraph(previous, line, ctx.flowPitch(), ctx.bodySize())) {
            return false;
        }
        return !heading || (Math.abs(line.fontSize() - previous.fontSize()) < 0.5f
                && !NUMBERING.matcher(line.text()).find());
    }

    /**
     * A new paragraph starts at a larger distance than usual (proportionally more for larger fonts)
     * and at every list item.
     */
    private static boolean newParagraph(Line previous, Line line, float pitch, float bodySize) {
        float scale = Math.max(1f, previous.fontSize() / bodySize);
        return line.baseline() - previous.baseline() > PARAGRAPH_GAP * pitch * scale
                || LIST_ITEM.matcher(line.text()).find();
    }

    private static void flush(List<Line> lines, boolean heading, List<Block> blocks, Context ctx) {
        if (lines.isEmpty()) {
            return;
        }
        String text = join(lines, ctx.vocabulary());
        blocks.add(heading ? new Heading(level(text), text) : new Paragraph(text));
        lines.clear();
    }

    /** Outline level from the numbering: Teil → 1, "3." → 2, "3.1" → 3, Anlage A → 2, A.1 → 3. */
    static int level(String heading) {
        Matcher m = NUMBERING.matcher(heading);
        if (!m.find() || m.group(1) != null) {
            return 1;
        }
        String numbers = m.group(2) != null ? "A" + m.group(2) : m.group(3);
        return Math.min(numbers.split("\\.").length + 1, 6);
    }

    static List<Line> lines(List<Word> words) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Word::baseline));
        List<List<Word>> groups = new ArrayList<>();
        for (Word word : sorted) {
            if (groups.isEmpty() || word.baseline() - groups.getLast().getFirst().baseline() > SAME_LINE) {
                groups.add(new ArrayList<>());
            }
            groups.getLast().add(word);
        }
        List<Line> lines = new ArrayList<>();
        for (List<Word> group : groups) {
            group.sort(Comparator.comparingDouble(Word::x));
            float top = (float) group.stream().mapToDouble(Word::top).min().orElseThrow();
            float fontSize = (float) group.stream().mapToDouble(Word::fontSize).max().orElseThrow();
            boolean bold = group.stream().allMatch(Word::bold);
            lines.add(new Line(group, top, group.getFirst().baseline(), fontSize, bold));
        }
        return lines;
    }

    private static String join(List<Line> lines, Set<String> vocabulary) {
        String text = "";
        for (Line line : lines) {
            text = text.isEmpty() ? line.text() : joinText(text, line.text(), vocabulary);
        }
        return text;
    }

    /**
     * Joins two lines. If the first ends with a hyphen, it is kept without a space – except for
     * hyphenation: the second line starts in lower case and the joined word occurs in the document
     * ("techni-" + "sche" → "technische").
     */
    static String joinText(String first, String second, Set<String> vocabulary) {
        if (!first.endsWith("-")) {
            return first + " " + second;
        }
        String head = lastWord(first.substring(0, first.length() - 1));
        String tail = firstWord(second);
        boolean syllable = !head.isEmpty() && !tail.isEmpty() && Character.isLowerCase(tail.charAt(0))
                && vocabulary.contains((head + tail).toLowerCase(Locale.ROOT));
        return (syllable ? first.substring(0, first.length() - 1) : first) + second;
    }

    private static String lastWord(String text) {
        int i = text.length();
        while (i > 0 && Character.isLetter(text.charAt(i - 1))) {
            i--;
        }
        return text.substring(i);
    }

    private static String firstWord(String text) {
        int i = 0;
        while (i < text.length() && Character.isLetter(text.charAt(i))) {
            i++;
        }
        return text.substring(0, i);
    }
}
