package pdf2odt;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import pdf2odt.Model.Image;

/**
 * Liest die Bilder und die waagrechten/senkrechten Linien einer Seite.
 * Koordinaten wie bei {@link PageExtractor}: y zählt von der Seitenoberkante nach unten.
 */
final class GraphicsExtractor extends PDFGraphicsStreamEngine {

    /** Ein Bild mit seiner Oberkante auf der Seite. */
    record PlacedImage(float top, Image image) {
    }

    /** Eine waagrechte ({@code horizontal}) oder senkrechte Linie bei {@code pos} von {@code from} bis {@code to}. */
    record Segment(boolean horizontal, float pos, float from, float to) {
    }

    record Graphics(List<PlacedImage> images, List<Segment> segments) {
    }

    private static final double PT_TO_CM = 2.54 / 72;
    /** Gefüllte Rechtecke bis zu dieser Dicke gelten als Linie (Word und InDesign zeichnen Linien so). */
    private static final double MAX_LINE_WIDTH = 3;

    private final PDRectangle cropBox;
    private final List<PlacedImage> images = new ArrayList<>();
    private final List<Segment> segments = new ArrayList<>();

    /** Der aktuelle Pfad: Rechtecke und gerade Linienstücke in Seitenkoordinaten. */
    private final List<Rectangle2D> pathRects = new ArrayList<>();
    private final List<Point2D[]> pathLines = new ArrayList<>();
    private Point2D current = new Point2D.Float();
    private Point2D subpathStart = current;

    private GraphicsExtractor(PDPage page) {
        super(page);
        cropBox = page.getCropBox();
    }

    static Graphics extract(PDPage page) throws IOException {
        GraphicsExtractor extractor = new GraphicsExtractor(page);
        extractor.processPage(page);
        return new Graphics(extractor.images, extractor.segments);
    }

    /** Umrechnung von PDF-Koordinaten (y nach oben) in Seitenkoordinaten (y nach unten). */
    private Point2D toPage(double x, double y) {
        return new Point2D.Double(x - cropBox.getLowerLeftX(), cropBox.getUpperRightY() - y);
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        // Das Bild füllt das Einheitsquadrat, abgebildet durch die aktuelle Transformationsmatrix.
        Rectangle2D bounds = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform()
                .createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1)).getBounds2D();
        BufferedImage image = pdImage.getImage();
        if (ImageHash.isBlank(image)) {
            return;
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        float top = (float) toPage(0, bounds.getMaxY()).getY();
        images.add(new PlacedImage(top, new Image(png.toByteArray(),
                bounds.getWidth() * PT_TO_CM, bounds.getHeight() * PT_TO_CM, ImageHash.of(image))));
    }

    // ---------------------------------------------------------------- Pfad aufbauen

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        Rectangle2D rect = new Rectangle2D.Double();
        rect.setFrameFromDiagonal(toPage(p0.getX(), p0.getY()), toPage(p2.getX(), p2.getY()));
        pathRects.add(rect);
        current = p0;
        subpathStart = p0;
    }

    @Override
    public void moveTo(float x, float y) {
        current = new Point2D.Float(x, y);
        subpathStart = current;
    }

    @Override
    public void lineTo(float x, float y) {
        Point2D next = new Point2D.Float(x, y);
        pathLines.add(new Point2D[] {toPage(current.getX(), current.getY()), toPage(x, y)});
        current = next;
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        current = new Point2D.Float(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return current;
    }

    @Override
    public void closePath() {
        lineTo((float) subpathStart.getX(), (float) subpathStart.getY());
    }

    // ---------------------------------------------------------------- Pfad zeichnen

    @Override
    public void strokePath() {
        for (Rectangle2D r : pathRects) {
            addSegment(r.getMinX(), r.getMinY(), r.getMaxX(), r.getMinY());
            addSegment(r.getMinX(), r.getMaxY(), r.getMaxX(), r.getMaxY());
            addSegment(r.getMinX(), r.getMinY(), r.getMinX(), r.getMaxY());
            addSegment(r.getMaxX(), r.getMinY(), r.getMaxX(), r.getMaxY());
        }
        for (Point2D[] line : pathLines) {
            addSegment(line[0].getX(), line[0].getY(), line[1].getX(), line[1].getY());
        }
        endPath();
    }

    @Override
    public void fillPath(int windingRule) {
        for (Rectangle2D r : pathRects) {
            double thin = Math.min(r.getWidth(), r.getHeight());
            double length = Math.max(r.getWidth(), r.getHeight());
            if (thin <= MAX_LINE_WIDTH && length > 3 * thin) {
                if (r.getWidth() > r.getHeight()) {
                    addSegment(r.getMinX(), r.getCenterY(), r.getMaxX(), r.getCenterY());
                } else {
                    addSegment(r.getCenterX(), r.getMinY(), r.getCenterX(), r.getMaxY());
                }
            }
        }
        endPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        strokePath();
    }

    @Override
    public void endPath() {
        pathRects.clear();
        pathLines.clear();
    }

    @Override
    public void clip(int windingRule) {
        // Der Pfad wird anschließend mit endPath() verworfen.
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }

    /** Übernimmt nur waagrechte oder senkrechte Linien. */
    private void addSegment(double x1, double y1, double x2, double y2) {
        if (Math.abs(y1 - y2) < 0.5 && Math.abs(x1 - x2) >= 0.5) {
            segments.add(new Segment(true, (float) ((y1 + y2) / 2), (float) Math.min(x1, x2), (float) Math.max(x1, x2)));
        } else if (Math.abs(x1 - x2) < 0.5 && Math.abs(y1 - y2) >= 0.5) {
            segments.add(new Segment(false, (float) ((x1 + x2) / 2), (float) Math.min(y1, y2), (float) Math.max(y1, y2)));
        }
    }
}
