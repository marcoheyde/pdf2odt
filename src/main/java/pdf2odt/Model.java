package pdf2odt;

import java.util.List;

/** Format-independent document model: a flat sequence of blocks. */
final class Model {

    private Model() {
    }

    sealed interface Block permits Heading, Paragraph, Table, Image {
    }

    record Heading(int level, String text) implements Block {
    }

    record Paragraph(String text) implements Block {
    }

    /**
     * Table as a grid: every row has the same number of positions.
     * {@code null} marks a position covered by a merged cell.
     */
    record Table(List<List<Cell>> rows, List<Double> columnWidthsCm) implements Block {
    }

    /**
     * A table cell. Its content is always a single paragraph: Writer compares table rows only as a whole,
     * so paragraphs within a cell do not help the comparison, but differing paragraph splits (e.g. Word
     * vs. InDesign spacing) would mark otherwise identical rows as changed.
     */
    record Cell(String text, int colSpan, int rowSpan) {
    }

    record Image(byte[] png, double widthCm, double heightCm, String hash) implements Block {
    }
}
