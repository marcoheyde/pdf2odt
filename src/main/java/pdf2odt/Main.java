package pdf2odt;

import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Usage: {@code java -jar pdf2odt.jar input.pdf output.odt} */
public final class Main {

    /** PDFBox reports e.g. missing fonts; this is irrelevant for text extraction. */
    private static final Logger PDFBOX_LOG = Logger.getLogger("org.apache");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java -jar pdf2odt.jar input.pdf output.odt");
            System.exit(2);
        }
        // Images are processed with AWT; no display is needed (e.g. Linux servers without X11).
        System.setProperty("java.awt.headless", "true");
        PDFBOX_LOG.setLevel(Level.SEVERE);
        try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {
            OdtWriter.write(DocumentBuilder.convert(doc), Path.of(args[1]));
        } catch (NoSuchFileException e) {
            System.err.println("File not found: " + e.getFile());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Conversion failed: " + e);
            System.exit(1);
        }
    }
}
