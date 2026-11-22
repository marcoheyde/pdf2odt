package pdf2odt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FigureDetectorTest {

    private static final double PAGE_WIDTH = 595;
    private static final double PAGE_HEIGHT = 842;

    @Test
    void nearbyCurvesFormADrawingGrownByTouchingBoxesAndLabels() {
        List<Rectangle2D> marks = drawing(); // e.g. arrow heads, rounded shapes
        Rectangle2D frame = box(95, 95, 110, 60);    // box frame around the curves
        Rectangle2D label = box(100, 157, 40, 8);    // label just below the frame
        Rectangle2D caption = box(100, 180, 200, 8); // caption further below

        List<Rectangle2D> figures = FigureDetector.detect(marks, List.of(frame), List.of(label, caption),
                PAGE_WIDTH, PAGE_HEIGHT);

        assertEquals(1, figures.size());
        Rectangle2D figure = figures.getFirst();
        assertTrue(figure.contains(frame));
        assertTrue(figure.contains(label));
        assertTrue(!figure.intersects(caption));
    }

    @Test
    void fewOrSmallMarksAreNoDrawing() {
        List<Rectangle2D> fewMarks = List.of(box(100, 100, 50, 50), box(200, 100, 50, 50));
        List<Rectangle2D> smallMarks = List.of(box(100, 100, 2, 2), box(103, 100, 2, 2), box(106, 100, 2, 2),
                box(109, 100, 2, 2), box(112, 100, 2, 2));

        assertTrue(FigureDetector.detect(fewMarks, List.of(), List.of(), PAGE_WIDTH, PAGE_HEIGHT).isEmpty());
        assertTrue(FigureDetector.detect(smallMarks, List.of(), List.of(), PAGE_WIDTH, PAGE_HEIGHT).isEmpty());
    }

    @Test
    void pageBackgroundDoesNotSwallowThePage() {
        List<Rectangle2D> marks = drawing();
        Rectangle2D background = box(0, 0, PAGE_WIDTH, PAGE_HEIGHT);

        Rectangle2D figure = FigureDetector.detect(marks, List.of(background), List.of(), PAGE_WIDTH, PAGE_HEIGHT)
                .getFirst();

        assertTrue(figure.getWidth() < 200);
    }

    /** Six marks of 15×20 pt in two rows, 5 pt apart: 55×45 pt in total. */
    private static List<Rectangle2D> drawing() {
        List<Rectangle2D> marks = new ArrayList<>();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                marks.add(box(100 + col * 20, 100 + row * 25, 15, 20));
            }
        }
        return marks;
    }

    private static Rectangle2D box(double x, double y, double w, double h) {
        return new Rectangle2D.Double(x, y, w, h);
    }
}
