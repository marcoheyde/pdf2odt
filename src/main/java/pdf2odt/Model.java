package pdf2odt;

import java.util.List;

/** Formatunabhängiges Dokumentmodell: eine flache Folge von Blöcken. */
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
     * Tabelle als Raster: jede Zeile hat gleich viele Positionen.
     * {@code null} markiert eine Position, die von einer verbundenen Zelle überdeckt wird.
     */
    record Table(List<List<Cell>> rows, List<Double> columnWidthsCm) implements Block {
    }

    record Cell(List<String> paragraphs, int colSpan, int rowSpan) {
    }

    record Image(byte[] png, double widthCm, double heightCm, String hash) implements Block {
    }
}
