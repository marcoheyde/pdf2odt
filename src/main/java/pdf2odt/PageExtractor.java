package pdf2odt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Liest die Wörter jeder Seite mit Position und Schrift.
 * Alle y-Koordinaten zählen von der Seitenoberkante nach unten.
 */
final class PageExtractor extends PDFTextStripper {

    /** Ein Wort; {@code baseline} ist die Grundlinie, {@code top} die Oberkante. */
    record Word(String text, float x, float right, float top, float baseline, float fontSize, boolean bold) {
    }

    private final List<List<Word>> pages = new ArrayList<>();

    private PageExtractor() {
        setSortByPosition(true);
    }

    static List<List<Word>> words(PDDocument doc) throws IOException {
        PageExtractor extractor = new PageExtractor();
        extractor.getText(doc);
        return extractor.pages;
    }

    @Override
    protected void startPage(PDPage page) {
        pages.add(new ArrayList<>());
    }

    /** PDFBox liefert hier Textstücke; sie werden an Leerzeichen in einzelne Wörter zerlegt. */
    @Override
    protected void writeString(String text, List<TextPosition> positions) {
        List<TextPosition> word = new ArrayList<>();
        for (TextPosition p : positions) {
            if (p.getUnicode().isBlank()) {
                addWord(word);
                word.clear();
            } else {
                word.add(p);
            }
        }
        addWord(word);
    }

    private void addWord(List<TextPosition> chars) {
        if (chars.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        float top = Float.MAX_VALUE;
        for (TextPosition c : chars) {
            text.append(symbol(c.getUnicode()));
            top = Math.min(top, c.getYDirAdj() - c.getHeightDir());
        }
        TextPosition first = chars.getFirst();
        TextPosition last = chars.getLast();
        pages.getLast().add(new Word(text.toString(), first.getXDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(),
                top, first.getYDirAdj(), first.getFontSizeInPt(), isBold(first.getFont())));
    }

    /**
     * Zeichen der Schriften Symbol und Wingdings ohne Unicode-Zuordnung liefert PDFBox als Zeichen aus
     * dem privaten Bereich (U+F0xx); die in Aufzählungen üblichen werden in echte Zeichen übersetzt.
     */
    private static String symbol(String text) {
        return switch (text) {
            case "" -> "•";
            case "" -> "▪";
            case "" -> "➢";
            case "" -> "→";
            default -> text;
        };
    }

    private static boolean isBold(PDFont font) {
        if (font == null) {
            return false;
        }
        PDFontDescriptor descriptor = font.getFontDescriptor();
        if (descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 700)) {
            return true;
        }
        return font.getName() != null && font.getName().toLowerCase(Locale.ROOT).contains("bold");
    }
}
