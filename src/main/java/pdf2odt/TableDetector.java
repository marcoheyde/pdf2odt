package pdf2odt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import pdf2odt.GraphicsExtractor.Segment;
import pdf2odt.PageExtractor.Word;

/** Erkennt Tabellen an ihren Gitterlinien und ordnet die Wörter den Zellen zu. */
final class TableDetector {

    /** Linien, die so nah beieinander liegen, gelten als dieselbe Linie bzw. als verbunden. */
    private static final float TOLERANCE = 3f;

    /** Eine Zelle mit ihren Wörtern; verbundene Zellen überspannen mehrere Rasterzellen. */
    record WordCell(List<Word> words, int colSpan, int rowSpan) {
    }

    /**
     * Eine erkannte Tabelle: Raster aus Zellen ({@code null} = von einer verbundenen Zelle überdeckt),
     * Spaltenbreiten in pt, Oberkante und alle Wörter, die zu ihr gehören.
     */
    record DetectedTable(float top, List<List<WordCell>> rows, List<Float> columnWidths, Set<Word> words) {
    }

    private TableDetector() {
    }

    static List<DetectedTable> detect(List<Segment> segments, List<Word> words) {
        List<DetectedTable> tables = new ArrayList<>();
        for (List<Segment> group : connectedGroups(segments)) {
            DetectedTable table = toTable(group, words);
            if (table != null) {
                tables.add(table);
            }
        }
        return tables;
    }

    // ---------------------------------------------------------------- Linien gruppieren

    /** Fasst Linien zusammen, die sich (über andere Linien) berühren oder kreuzen. */
    static List<List<Segment>> connectedGroups(List<Segment> segments) {
        int n = segments.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (touch(segments.get(i), segments.get(j))) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        List<List<Segment>> groups = new ArrayList<>();
        int[] groupOf = new int[n];
        Arrays.fill(groupOf, -1);
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            if (groupOf[root] < 0) {
                groupOf[root] = groups.size();
                groups.add(new ArrayList<>());
            }
            groups.get(groupOf[root]).add(segments.get(i));
        }
        return groups;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static boolean touch(Segment a, Segment b) {
        if (a.horizontal() == b.horizontal()) {
            return Math.abs(a.pos() - b.pos()) <= TOLERANCE
                    && a.from() <= b.to() + TOLERANCE && b.from() <= a.to() + TOLERANCE;
        }
        return a.from() - TOLERANCE <= b.pos() && b.pos() <= a.to() + TOLERANCE
                && b.from() - TOLERANCE <= a.pos() && a.pos() <= b.to() + TOLERANCE;
    }

    // ---------------------------------------------------------------- Raster und Zellen

    private static DetectedTable toTable(List<Segment> group, List<Word> words) {
        List<Segment> horizontal = group.stream().filter(Segment::horizontal).toList();
        List<Segment> vertical = group.stream().filter(s -> !s.horizontal()).toList();
        float[] ys = boundaries(horizontal);
        float[] xs = boundaries(vertical);
        if (ys.length < 2 || xs.length < 2) {
            return null;
        }
        int rows = ys.length - 1;
        int cols = xs.length - 1;

        // Benachbarte Rasterzellen ohne trennende Linie gehören zur selben (verbundenen) Zelle.
        int[] parent = new int[rows * cols];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float midY = (ys[r] + ys[r + 1]) / 2;
                float midX = (xs[c] + xs[c + 1]) / 2;
                if (c + 1 < cols && !covered(vertical, xs[c + 1], midY)) {
                    parent[find(parent, r * cols + c)] = find(parent, r * cols + c + 1);
                }
                if (r + 1 < rows && !covered(horizontal, ys[r + 1], midX)) {
                    parent[find(parent, r * cols + c)] = find(parent, (r + 1) * cols + c);
                }
            }
        }

        // Ausdehnung jeder verbundenen Zelle; ihr Anker ist die Rasterzelle oben links.
        int[] minR = new int[parent.length];
        int[] maxR = new int[parent.length];
        int[] minC = new int[parent.length];
        int[] maxC = new int[parent.length];
        Arrays.fill(minR, Integer.MAX_VALUE);
        Arrays.fill(minC, Integer.MAX_VALUE);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int root = find(parent, r * cols + c);
                minR[root] = Math.min(minR[root], r);
                maxR[root] = Math.max(maxR[root], r);
                minC[root] = Math.min(minC[root], c);
                maxC[root] = Math.max(maxC[root], c);
            }
        }

        // Wörter nach ihrem Mittelpunkt den Zellen zuordnen.
        List<List<Word>> cellWords = new ArrayList<>();
        for (int i = 0; i < parent.length; i++) {
            cellWords.add(new ArrayList<>());
        }
        Set<Word> used = new HashSet<>();
        for (Word w : words) {
            int c = index(xs, (w.x() + w.right()) / 2);
            int r = index(ys, (w.top() + w.baseline()) / 2);
            if (r >= 0 && c >= 0) {
                cellWords.get(find(parent, r * cols + c)).add(w);
                used.add(w);
            }
        }

        List<List<WordCell>> table = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<WordCell> row = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                int root = find(parent, r * cols + c);
                if (minR[root] == r && minC[root] == c) {
                    row.add(new WordCell(cellWords.get(root), maxC[root] - c + 1, maxR[root] - r + 1));
                } else {
                    row.add(null);
                }
            }
            table.add(row);
        }
        List<Float> widths = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            widths.add(xs[c + 1] - xs[c]);
        }
        return new DetectedTable(ys[0], table, widths, used);
    }

    /** Sortierte, zusammengefasste Positionen der Linien (Zeilen- bzw. Spaltengrenzen). */
    private static float[] boundaries(List<Segment> segments) {
        float[] positions = new float[segments.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = segments.get(i).pos();
        }
        Arrays.sort(positions);
        List<Float> result = new ArrayList<>();
        for (float p : positions) {
            if (result.isEmpty() || p - result.getLast() > TOLERANCE) {
                result.add(p);
            }
        }
        float[] array = new float[result.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = result.get(i);
        }
        return array;
    }

    /** Gibt es eine Linie bei {@code pos}, die die Stelle {@code at} überdeckt? */
    private static boolean covered(List<Segment> segments, float pos, float at) {
        for (Segment s : segments) {
            if (Math.abs(s.pos() - pos) <= TOLERANCE && s.from() - TOLERANCE <= at && at <= s.to() + TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** Index des Intervalls zwischen zwei Grenzen, das {@code value} enthält, sonst -1. */
    private static int index(float[] bounds, float value) {
        for (int i = 0; i + 1 < bounds.length; i++) {
            if (bounds[i] <= value && value < bounds[i + 1]) {
                return i;
            }
        }
        return -1;
    }
}
