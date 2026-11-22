package pdf2odt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pdf2odt.Model.Cell;

class OdtWriterTest {

    @TempDir
    Path dir;

    @Test
    void writesValidOdtPackage() throws Exception {
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        List<Model.Block> blocks = List.of(
                new Model.Heading(1, "1 Einleitung"),
                new Model.Paragraph("Text mit <Sonderzeichen> & Umlauten äöü."),
                new Model.Table(List.of(
                        List.of(new Cell("Feld", 1, 1), new Cell("Typ", 1, 1)),
                        Arrays.asList(new Cell("über zwei Spalten", 2, 1), null)), List.of(3.0, 9.0)),
                new Model.Image(png.toByteArray(), 2.0, 2.0, "0123456789abcdef"));

        Path odt = dir.resolve("test.odt");
        OdtWriter.write(blocks, odt);

        try (var zip = new ZipFile(odt.toFile())) {
            ZipEntry first = zip.entries().nextElement();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
            assertNotNull(zip.getEntry("Pictures/image1.png"));

            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            for (String name : List.of("META-INF/manifest.xml", "styles.xml", "content.xml")) {
                factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(name)));
            }
            String content = new String(zip.getInputStream(zip.getEntry("content.xml")).readAllBytes());
            assertTrue(content.contains("text:outline-level=\"1\""));
            assertTrue(content.contains("table:number-columns-spanned=\"2\""));
            assertTrue(content.contains("table:covered-table-cell"));
            assertTrue(content.contains("[Image 0123456789abcdef]"));
            // every table row is a separate table with the column widths from the PDF
            assertTrue(content.contains("table:name=\"Table1_1\""));
            assertTrue(content.contains("table:name=\"Table1_2\""));
            assertTrue(content.contains("style:column-width=\"9.00cm\""));
        }
    }
}
