package pdf2odt;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import pdf2odt.Model.Block;
import pdf2odt.Model.Cell;
import pdf2odt.Model.Heading;
import pdf2odt.Model.Image;
import pdf2odt.Model.Paragraph;
import pdf2odt.Model.Table;

/** Writes a sequence of blocks as an ODT file (ODF 1.3). */
final class OdtWriter {

    private static final String MIMETYPE = "application/vnd.oasis.opendocument.text";

    private static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    private static final String SVG = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";
    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private static final String MANIFEST = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";

    private OdtWriter() {
    }

    static void write(List<Block> blocks, Path file) throws IOException {
        Map<String, byte[]> pictures = new LinkedHashMap<>();
        for (Block block : blocks) {
            if (block instanceof Image image) {
                pictures.put(picturePath(pictures.size() + 1), image.png());
            }
        }

        try (OutputStream out = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            writeMimetype(zip);
            putEntry(zip, "META-INF/manifest.xml", xml(w -> writeManifest(w, pictures.keySet())));
            putEntry(zip, "styles.xml", xml(OdtWriter::writeStyles));
            putEntry(zip, "content.xml", xml(w -> writeContent(w, blocks)));
            for (Map.Entry<String, byte[]> picture : pictures.entrySet()) {
                putEntry(zip, picture.getKey(), picture.getValue());
            }
        }
    }

    private static String picturePath(int number) {
        return "Pictures/image" + number + ".png";
    }

    /** The "mimetype" entry must be stored first and uncompressed. */
    private static void writeMimetype(ZipOutputStream zip) throws IOException {
        byte[] bytes = MIMETYPE.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    // ---------------------------------------------------------------- manifest.xml

    private static void writeManifest(XMLStreamWriter w, Iterable<String> pictures) throws XMLStreamException {
        w.writeStartElement("manifest", "manifest", MANIFEST);
        w.writeNamespace("manifest", MANIFEST);
        w.writeAttribute("manifest", MANIFEST, "version", "1.3");
        fileEntry(w, "/", MIMETYPE);
        fileEntry(w, "content.xml", "text/xml");
        fileEntry(w, "styles.xml", "text/xml");
        for (String picture : pictures) {
            fileEntry(w, picture, "image/png");
        }
        w.writeEndElement();
    }

    private static void fileEntry(XMLStreamWriter w, String path, String mediaType) throws XMLStreamException {
        w.writeEmptyElement("manifest", "file-entry", MANIFEST);
        w.writeAttribute("manifest", MANIFEST, "full-path", path);
        if (path.equals("/")) {
            w.writeAttribute("manifest", MANIFEST, "version", "1.3");
        }
        w.writeAttribute("manifest", MANIFEST, "media-type", mediaType);
    }

    // ---------------------------------------------------------------- styles.xml

    private static void writeStyles(XMLStreamWriter w) throws XMLStreamException {
        w.writeStartElement("office", "document-styles", OFFICE);
        w.writeNamespace("office", OFFICE);
        w.writeNamespace("style", STYLE);
        w.writeNamespace("fo", FO);
        w.writeAttribute("office", OFFICE, "version", "1.3");
        w.writeStartElement("office", "styles", OFFICE);

        paragraphStyle(w, "Standard", null, null);
        paragraphStyle(w, "Heading", "Heading", "Standard");
        for (int level = 1; level <= 6; level++) {
            w.writeStartElement("style", "style", STYLE);
            w.writeAttribute("style", STYLE, "name", "Heading_20_" + level);
            w.writeAttribute("style", STYLE, "display-name", "Heading " + level);
            w.writeAttribute("style", STYLE, "family", "paragraph");
            w.writeAttribute("style", STYLE, "parent-style-name", "Heading");
            w.writeAttribute("style", STYLE, "default-outline-level", String.valueOf(level));
            w.writeEmptyElement("style", "paragraph-properties", STYLE);
            w.writeAttribute("fo", FO, "margin-top", "0.4cm");
            w.writeAttribute("fo", FO, "margin-bottom", "0.2cm");
            w.writeEmptyElement("style", "text-properties", STYLE);
            w.writeAttribute("fo", FO, "font-size", Math.max(18 - 2 * level, 11) + "pt");
            w.writeAttribute("fo", FO, "font-weight", "bold");
            w.writeEndElement();
        }

        w.writeEndElement();
        w.writeEndElement();
    }

    private static void paragraphStyle(XMLStreamWriter w, String name, String className, String parent)
            throws XMLStreamException {
        w.writeEmptyElement("style", "style", STYLE);
        w.writeAttribute("style", STYLE, "name", name);
        w.writeAttribute("style", STYLE, "family", "paragraph");
        if (className != null) {
            w.writeAttribute("style", STYLE, "class", className.toLowerCase(Locale.ROOT));
        }
        if (parent != null) {
            w.writeAttribute("style", STYLE, "parent-style-name", parent);
        }
    }

    // ---------------------------------------------------------------- content.xml

    private static void writeContent(XMLStreamWriter w, List<Block> blocks) throws XMLStreamException {
        w.writeStartElement("office", "document-content", OFFICE);
        w.writeNamespace("office", OFFICE);
        w.writeNamespace("style", STYLE);
        w.writeNamespace("text", TEXT);
        w.writeNamespace("table", TABLE);
        w.writeNamespace("draw", DRAW);
        w.writeNamespace("fo", FO);
        w.writeNamespace("svg", SVG);
        w.writeNamespace("xlink", XLINK);
        w.writeAttribute("office", OFFICE, "version", "1.3");
        writeTableStyles(w, blocks.stream().filter(Table.class::isInstance).map(Table.class::cast).toList());
        w.writeStartElement("office", "body", OFFICE);
        w.writeStartElement("office", "text", OFFICE);

        int tableCount = 0;
        int imageCount = 0;
        for (Block block : blocks) {
            switch (block) {
                case Heading h -> writeHeading(w, h);
                case Paragraph p -> writeParagraph(w, p.text());
                case Table t -> writeTable(w, t, ++tableCount);
                case Image i -> writeImage(w, i, ++imageCount);
            }
        }

        w.writeEndElement();
        w.writeEndElement();
        w.writeEndElement();
    }

    /** One table style per table and one column style per column with the width from the PDF; plus a cell border. */
    private static void writeTableStyles(XMLStreamWriter w, List<Table> tables) throws XMLStreamException {
        w.writeStartElement("office", "automatic-styles", OFFICE);
        w.writeStartElement("style", "style", STYLE);
        w.writeAttribute("style", STYLE, "name", "Cell");
        w.writeAttribute("style", STYLE, "family", "table-cell");
        w.writeEmptyElement("style", "table-cell-properties", STYLE);
        w.writeAttribute("fo", FO, "border", "0.5pt solid #808080");
        w.writeAttribute("fo", FO, "padding", "0.05cm");
        w.writeEndElement();
        for (int t = 0; t < tables.size(); t++) {
            List<Double> widths = tables.get(t).columnWidthsCm();
            w.writeStartElement("style", "style", STYLE);
            w.writeAttribute("style", STYLE, "name", "Table" + (t + 1));
            w.writeAttribute("style", STYLE, "family", "table");
            w.writeEmptyElement("style", "table-properties", STYLE);
            w.writeAttribute("style", STYLE, "width", cm(widths.stream().mapToDouble(Double::doubleValue).sum()));
            w.writeAttribute("table", TABLE, "align", "left");
            w.writeEndElement();
            for (int c = 0; c < widths.size(); c++) {
                w.writeStartElement("style", "style", STYLE);
                w.writeAttribute("style", STYLE, "name", "Table" + (t + 1) + "." + (c + 1));
                w.writeAttribute("style", STYLE, "family", "table-column");
                w.writeEmptyElement("style", "table-column-properties", STYLE);
                w.writeAttribute("style", STYLE, "column-width", cm(widths.get(c)));
                w.writeEndElement();
            }
        }
        w.writeEndElement();
    }

    private static void writeHeading(XMLStreamWriter w, Heading h) throws XMLStreamException {
        int level = Math.clamp(h.level(), 1, 6);
        w.writeStartElement("text", "h", TEXT);
        w.writeAttribute("text", TEXT, "style-name", "Heading_20_" + level);
        w.writeAttribute("text", TEXT, "outline-level", String.valueOf(level));
        w.writeCharacters(h.text());
        w.writeEndElement();
    }

    private static void writeParagraph(XMLStreamWriter w, String text) throws XMLStreamException {
        w.writeStartElement("text", "p", TEXT);
        w.writeAttribute("text", TEXT, "style-name", "Standard");
        w.writeCharacters(text);
        w.writeEndElement();
    }

    /**
     * Every table row becomes a separate single-row table: otherwise the Writer comparison marks the
     * whole table on any change, this way only the affected row. Cells merged across several rows
     * therefore appear only in their first row.
     */
    private static void writeTable(XMLStreamWriter w, Table t, int number) throws XMLStreamException {
        int columns = t.columnWidthsCm().size();
        for (int r = 0; r < t.rows().size(); r++) {
            w.writeStartElement("table", "table", TABLE);
            w.writeAttribute("table", TABLE, "name", "Table" + number + "_" + (r + 1));
            w.writeAttribute("table", TABLE, "style-name", "Table" + number);
            for (int c = 0; c < columns; c++) {
                w.writeEmptyElement("table", "table-column", TABLE);
                w.writeAttribute("table", TABLE, "style-name", "Table" + number + "." + (c + 1));
            }
            w.writeStartElement("table", "table-row", TABLE);
            List<Cell> row = t.rows().get(r);
            for (int c = 0; c < columns; c++) {
                Cell cell = row.get(c);
                int span = cell == null ? 1 : Math.min(cell.colSpan(), columns - c);
                w.writeStartElement("table", "table-cell", TABLE);
                w.writeAttribute("table", TABLE, "style-name", "Cell");
                w.writeAttribute("office", OFFICE, "value-type", "string");
                if (span > 1) {
                    w.writeAttribute("table", TABLE, "number-columns-spanned", String.valueOf(span));
                }
                writeParagraph(w, cell == null ? "" : cell.text());
                w.writeEndElement();
                for (int k = 1; k < span; k++) {
                    w.writeEmptyElement("table", "covered-table-cell", TABLE);
                }
                c += span - 1;
            }
            w.writeEndElement();
            w.writeEndElement();
        }
    }

    private static void writeImage(XMLStreamWriter w, Image i, int number) throws XMLStreamException {
        w.writeStartElement("text", "p", TEXT);
        w.writeAttribute("text", TEXT, "style-name", "Standard");
        w.writeStartElement("draw", "frame", DRAW);
        w.writeAttribute("draw", DRAW, "name", "Image" + number);
        w.writeAttribute("text", TEXT, "anchor-type", "as-char");
        w.writeAttribute("svg", SVG, "width", cm(i.widthCm()));
        w.writeAttribute("svg", SVG, "height", cm(i.heightCm()));
        w.writeEmptyElement("draw", "image", DRAW);
        w.writeAttribute("xlink", XLINK, "href", picturePath(number));
        w.writeAttribute("xlink", XLINK, "type", "simple");
        w.writeAttribute("xlink", XLINK, "show", "embed");
        w.writeAttribute("xlink", XLINK, "actuate", "onLoad");
        w.writeEndElement();
        w.writeEndElement();
        writeParagraph(w, "[Image " + i.hash() + "]");
    }

    private static String cm(double value) {
        return String.format(Locale.ROOT, "%.2fcm", value);
    }

    // ---------------------------------------------------------------- XML helpers

    private interface XmlBody {
        void write(XMLStreamWriter w) throws XMLStreamException;
    }

    private static byte[] xml(XmlBody body) {
        try {
            var buffer = new java.io.ByteArrayOutputStream();
            XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(buffer, "UTF-8");
            w.writeStartDocument("UTF-8", "1.0");
            body.write(w);
            w.writeEndDocument();
            w.close();
            return buffer.toByteArray();
        } catch (XMLStreamException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
