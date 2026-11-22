package pdf2odt;

import java.io.File;
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
        PDFBOX_LOG.setLevel(Level.SEVERE);
        try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {
            OdtWriter.write(DocumentBuilder.convert(doc), Path.of(args[1]));
        }
    }
}
