package pdf2odt;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Aufruf: {@code java -jar pdf2odt.jar eingabe.pdf ausgabe.odt} */
public final class Main {

    /** PDFBox meldet z. B. fehlende Schriften; für die Textextraktion ist das unerheblich. */
    private static final Logger PDFBOX_LOG = Logger.getLogger("org.apache");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Aufruf: java -jar pdf2odt.jar eingabe.pdf ausgabe.odt");
            System.exit(2);
        }
        PDFBOX_LOG.setLevel(Level.SEVERE);
        try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {
            OdtWriter.write(DocumentBuilder.convert(doc), Path.of(args[1]));
        }
    }
}
