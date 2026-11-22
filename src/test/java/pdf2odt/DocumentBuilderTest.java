package pdf2odt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import pdf2odt.Model.Block;
import pdf2odt.Model.Cell;
import pdf2odt.Model.Paragraph;
import pdf2odt.Model.Table;
import pdf2odt.PageExtractor.Word;

class DocumentBuilderTest {

    @Test
    void repeatedPageHeaderIsRemoved() {
        List<List<Word>> pages = new ArrayList<>();
        List<String> lastWords = List.of("Puffer", "Kennung", "Anlage", "Port", "Format");
        for (int page = 1; page <= 5; page++) {
            pages.add(new ArrayList<>(List.of(
                    word("BUNDESNETZAGENTUR", 40), word("|", 40), word(String.valueOf(page), 40),
                    word("Text", 100), word(lastWords.get(page - 1), 700))));
        }

        DocumentBuilder.removeHeadersAndFooters(pages, List.of(842f, 842f, 842f, 842f, 842f));

        assertEquals(List.of("Text", "Puffer"), pages.getFirst().stream().map(Word::text).toList());
    }

    @Test
    void paragraphWithoutSentenceEndContinuesOnNextPage() {
        List<Block> blocks = DocumentBuilder.merge(List.of(
                List.of(new Paragraph("Der Puffer muss so ausgelegt")),
                List.of(new Paragraph("werden, dass nichts verloren geht."), new Paragraph("Neuer Absatz."))),
                Set.of());

        assertEquals(List.of(new Paragraph("Der Puffer muss so ausgelegt werden, dass nichts verloren geht."),
                new Paragraph("Neuer Absatz.")), blocks);
    }

    @Test
    void completeParagraphIsNotMerged() {
        List<Block> blocks = DocumentBuilder.merge(List.of(
                List.of(new Paragraph("Erster Satz.")), List.of(new Paragraph("Zweiter Satz."))), Set.of());

        assertEquals(2, blocks.size());
    }

    @Test
    void continuedTableIsMergedWithoutRepeatedHeader() {
        List<Cell> header = row("Feld", "Typ");
        Table first = new Table(List.of(header, row("LIID", "String")), List.of(3.0, 9.0));
        Table second = new Table(List.of(header, row("CIN", "Integer")), List.of(3.0, 9.0));

        List<Block> blocks = DocumentBuilder.merge(List.of(List.of(first), List.of(second)), Set.of());

        assertEquals(List.of(new Table(List.of(header, row("LIID", "String"), row("CIN", "Integer")),
                List.of(3.0, 9.0))), blocks);
    }

    @Test
    void hyphenationIsResolvedOnlyForKnownWords() {
        Set<String> vocabulary = Set.of("technische", "ip", "basierten");
        assertEquals("die technische Umsetzung", PageBuilder.joinText("die techni-", "sche Umsetzung", vocabulary));
        assertEquals("die IP-basierten Netze", PageBuilder.joinText("die IP-", "basierten Netze", vocabulary));
        assertEquals("mit E-Mail", PageBuilder.joinText("mit E-", "Mail", vocabulary));
    }

    private static Word word(String text, float baseline) {
        return new Word(text, 100, 150, baseline - 8, baseline, 9, false);
    }

    private static List<Cell> row(String... texts) {
        return List.of(texts).stream().map(t -> new Cell(List.of(t), 1, 1)).toList();
    }
}
