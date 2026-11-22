# pdf2odt

Konvertiert PDF-Schnittstellenbeschreibungen in ODT-Dateien, damit zwei Versionen in
LibreOffice Writer mit *Bearbeiten → Änderungen verfolgen → Dokument vergleichen*
verglichen werden können. Ziel ist ein gut vergleichbarer Text, kein originalgetreues Layout.

## Bauen und Aufruf

Voraussetzungen: Java 25, Maven 3.

```bash
mvn package
java -jar target/pdf2odt-0.1.0-SNAPSHOT.jar eingabe.pdf ausgabe.odt
```

## Was übernommen wird

- **Absätze:** Ein neuer Absatz beginnt, wenn der Zeilenabstand deutlich über dem im Dokument
  üblichen liegt (gemessen getrennt für Fließtext und Tabellenzellen), und bei jedem
  Aufzählungspunkt. Silbentrennungen am Zeilenende werden aufgelöst, wenn das Wort sonst
  ungetrennt im Dokument vorkommt.
- **Kopf- und Fußzeilen** werden entfernt: oberste bzw. unterste Zeile im Randbereich, die auf
  mindestens 20 % der Seiten gleich beginnt (Seitenzahlen zählen nicht).
- **Seitenumbrüche:** Ein Absatz ohne Satzende wird mit dem Anfang der Folgeseite verbunden,
  eine Tabelle mit ihrer Fortsetzung (wiederholte Kopfzeile entfällt).
- Das Inhaltsverzeichnis bleibt als normaler Text erhalten.
- **Überschriften:** fette Zeilen, die größer als der Fließtext oder mehrstufig nummeriert sind
  (`2.2.1`, `Anlage A.1`). Die Gliederungsebene folgt aus der Nummerierung
  (`Teil A` → 1, `3` → 2, `3.1` → 3, `Anlage A` → 2, `Anlage A.1` → 3).
  Seiten mit vielen Füllpunkt-Zeilen (Inhaltsverzeichnis) enthalten keine Überschriften.
- **Tabellen:** werden an ihren Gitterlinien erkannt, einschließlich verbundener Zellen.
  Jede Tabellenzeile wird als eigene einzeilige Tabelle geschrieben (mit gleichen Spaltenbreiten,
  sieht daher wie eine Tabelle aus): Der Writer-Vergleich markiert sonst bei jeder Änderung die
  ganze Tabelle, so nur die betroffene Zeile. Über mehrere Zeilen verbundene Zellen stehen nur in
  ihrer ersten Zeile.
- **Bilder:** werden als PNG übernommen. Unter jedem Bild steht eine Zeile `[Bild <hash>]`
  mit einem Wahrnehmungs-Hash. Ändert sich ein Bild inhaltlich, ändert sich diese Zeile,
  und der Writer-Vergleich zeigt die Änderung an. Einfarbige Bilder werden weggelassen.
