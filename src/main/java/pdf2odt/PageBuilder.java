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

/** Baut aus den Wörtern, Bildern und Tabellen einer Seite Überschriften, Absätze, Tabellen und Bilder. */
final class PageBuilder {

    /** Wörter, deren Grundlinien höchstens so weit auseinander liegen, bilden eine Zeile. */
    private static final float SAME_LINE = 2f;
    /** Ein Grundlinienabstand über dem üblichen Zeilenabstand × diesem Faktor beginnt einen neuen Absatz. */
    private static final float PARAGRAPH_GAP = 1.3f;
    /** Überschriften sind fett und mindestens um diesen Faktor größer als der Fließtext. */
    private static final float HEADING_SIZE = 1.1f;
    /** Ab so vielen Zeilen mit Füllpunkten gilt eine Seite als Inhaltsverzeichnis. */
    private static final int TOC_LINES = 5;
    private static final double PT_TO_CM = 2.54 / 72;

    /** "Teil A", "Anlage A.1.2" oder "3.1.2" bzw. "3." am Zeilenanfang. */
    private static final Pattern NUMBERING =
            Pattern.compile("^(?:(Teil) [A-Z]\\b|Anlage [A-Z]((?:\\.\\d+)*)\\b|(\\d+(?:\\.\\d+)*)\\.?(?:\\s|$))");

    /** Mehrstufige Nummerierung wie "2.2.1" oder "Anlage A.1"; einfache Listen wie "1." zählen nicht. */
    private static final Pattern DEEP_NUMBERING = Pattern.compile("^(?:\\d+(?:\\.\\d+)+|Anlage [A-Z](?:\\.\\d+)+)\\s");

    /**
     * Kennzahlen des ganzen Dokuments.
     *
     * @param bodySize   Schriftgröße des Fließtextes
     * @param flowPitch  üblicher Zeilenabstand im Fließtext
     * @param cellPitch  üblicher Zeilenabstand in Tabellenzellen
     * @param vocabulary alle Wörter des Dokuments (klein geschrieben), zum Auflösen von Silbentrennungen
     */
    /** Aufzählungspunkt am Zeilenanfang: "•", "-", "–", "a)" oder "1.". */
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[•\\-–]|[a-z]\\)|\\d+\\.)\\s");

    record Context(float bodySize, float flowPitch, float cellPitch, Set<String> vocabulary) {
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

    /** Ein Bild oder eine Tabelle mit Oberkante, einzusortieren zwischen die Textzeilen. */
    private record Placed(float top, Block block) {
    }

    private PageBuilder() {
    }

    /** @param words die Wörter außerhalb von Tabellen, ohne Kopf- und Fußzeilen */
    static List<Block> build(List<Word> words, List<PlacedImage> images, List<DetectedTable> tables, Context ctx) {
        List<Placed> pending = new ArrayList<>();
        images.forEach(i -> pending.add(new Placed(i.top(), i.image())));
        tables.forEach(t -> pending.add(new Placed(t.top(), toTable(t, ctx))));
        pending.sort(Comparator.comparingDouble(Placed::top));

        List<Line> lines = lines(words);
        // Seiten des Inhaltsverzeichnisses (viele Zeilen mit Füllpunkten) enthalten keine Überschriften.
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
                row.add(c == null ? null : new Cell(paragraphs(c.words(), ctx), c.colSpan(), c.rowSpan()));
            }
            rows.add(row);
        }
        List<Double> widths = detected.columnWidths().stream().map(w -> w * PT_TO_CM).toList();
        return new Table(rows, widths);
    }

    /** Absätze einer Tabellenzelle, ohne Überschriftenerkennung. */
    static List<String> paragraphs(List<Word> words, Context ctx) {
        List<String> paragraphs = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        for (Line line : lines(words)) {
            if (!current.isEmpty() && newParagraph(current.getLast(), line, ctx.cellPitch(), ctx.bodySize())) {
                paragraphs.add(join(current, ctx.vocabulary()));
                current.clear();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            paragraphs.add(join(current, ctx.vocabulary()));
        }
        return paragraphs;
    }

    /** Fett und entweder größer als der Fließtext oder mehrstufig nummeriert; muss Buchstaben enthalten. */
    private static boolean isHeading(Line line, float bodySize) {
        String text = line.text();
        return line.bold()
                && (line.fontSize() > bodySize * HEADING_SIZE || DEEP_NUMBERING.matcher(text).find())
                && text.codePoints().anyMatch(Character::isLetter);
    }

    /** Gehört {@code line} noch zum selben Absatz bzw. zur selben (mehrzeiligen) Überschrift? */
    private static boolean continues(Line previous, Line line, boolean previousIsHeading, boolean heading,
            Context ctx) {
        if (previousIsHeading != heading || newParagraph(previous, line, ctx.flowPitch(), ctx.bodySize())) {
            return false;
        }
        return !heading || (Math.abs(line.fontSize() - previous.fontSize()) < 0.5f
                && !NUMBERING.matcher(line.text()).find());
    }

    /**
     * Ein neuer Absatz beginnt bei größerem Abstand als üblich (bei größerer Schrift entsprechend mehr)
     * und bei jedem Aufzählungspunkt.
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

    /** Gliederungsebene aus der Nummerierung: Teil → 1, "3." → 2, "3.1" → 3, Anlage A → 2, A.1 → 3. */
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
     * Verbindet zwei Zeilen. Endet die erste mit Bindestrich, bleibt er ohne Leerzeichen stehen –
     * außer bei einer Silbentrennung: die zweite Zeile beginnt klein und das zusammengeschriebene
     * Wort kommt im Dokument vor ("techni-" + "sche" → "technische").
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
