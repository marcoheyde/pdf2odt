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
 * Reads the words of every page with position and font.
 * All y coordinates count downwards from the top edge of the page.
 */
final class PageExtractor extends PDFTextStripper {

    /** A word; {@code baseline} is the baseline, {@code top} the top edge. */
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

    /** PDFBox delivers text chunks here; they are split into single words at blanks. */
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
     * PDFBox returns characters of the Symbol and Wingdings fonts without a Unicode mapping as private use
     * characters (U+F0xx); those common in lists are translated into real characters.
     */
    private static String symbol(String text) {
        return switch (text) {
            case "\uF0B7" -> "•";
            case "\uF0A7" -> "▪";
            case "\uF0D8" -> "➢";
            case "\uF0E0" -> "→";
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
