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

    record Cell(List<String> paragraphs, int colSpan, int rowSpan) {
    }

    record Image(byte[] png, double widthCm, double heightCm, String hash) implements Block {
    }
}
