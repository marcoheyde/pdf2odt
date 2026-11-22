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
 * Reads the images, the horizontal/vertical lines and the outlines of all painted paths of a page.
 * Coordinates as in {@link PageExtractor}: y counts downwards from the top edge of the page.
 */
final class GraphicsExtractor extends PDFGraphicsStreamEngine {

    /** An image with its position on the page. */
    record PlacedImage(Rectangle2D bounds, Image image) {
        float top() {
            return (float) bounds.getMinY();
        }
    }

    /** A horizontal ({@code horizontal}) or vertical line at {@code pos} from {@code from} to {@code to}. */
    record Segment(boolean horizontal, float pos, float from, float to) {
    }

    /**
     * Everything painted on a page.
     *
     * @param marks  outlines of paths with curves or diagonal lines and of all images (incl. single-coloured
     *               ones): these occur in drawings, but not in tables
     * @param shapes outlines of all other painted paths (boxes, lines)
     */
    record Graphics(List<PlacedImage> images, List<Segment> segments, List<Rectangle2D> marks,
            List<Rectangle2D> shapes) {
    }

    private static final double PT_TO_CM = 2.54 / 72;
    /** Filled rectangles up to this thickness count as lines (Word and InDesign draw lines this way). */
    private static final double MAX_LINE_WIDTH = 3;

    private final PDRectangle cropBox;
    private final List<PlacedImage> images = new ArrayList<>();
    private final List<Segment> segments = new ArrayList<>();
    private final List<Rectangle2D> marks = new ArrayList<>();
    private final List<Rectangle2D> shapes = new ArrayList<>();

    /** The current path: rectangles and straight line pieces in page coordinates. */
    private final List<Rectangle2D> pathRects = new ArrayList<>();
    private final List<Point2D[]> pathLines = new ArrayList<>();
    private Point2D current = new Point2D.Float();
    private Point2D subpathStart = current;
    /** Outline of the current path, and whether it contains curves or diagonal lines. */
    private Rectangle2D pathBounds;
    private boolean pathIsDrawing;

    private GraphicsExtractor(PDPage page) {
        super(page);
        cropBox = page.getCropBox();
    }

    static Graphics extract(PDPage page) throws IOException {
        GraphicsExtractor extractor = new GraphicsExtractor(page);
        extractor.processPage(page);
        return new Graphics(extractor.images, extractor.segments, extractor.marks, extractor.shapes);
    }

    /** Converts PDF coordinates (y upwards) to page coordinates (y downwards). */
    private Point2D toPage(double x, double y) {
        return new Point2D.Double(x - cropBox.getLowerLeftX(), cropBox.getUpperRightY() - y);
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        // The image fills the unit square, mapped by the current transformation matrix.
        Rectangle2D bounds = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform()
                .createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1)).getBounds2D();
        Rectangle2D onPage = new Rectangle2D.Double();
        onPage.setFrameFromDiagonal(toPage(bounds.getMinX(), bounds.getMinY()), toPage(bounds.getMaxX(), bounds.getMaxY()));
        marks.add(onPage);
        BufferedImage image = pdImage.getImage();
        if (ImageHash.isBlank(image)) {
            return;
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        images.add(new PlacedImage(onPage, new Image(png.toByteArray(),
                bounds.getWidth() * PT_TO_CM, bounds.getHeight() * PT_TO_CM, ImageHash.of(image))));
    }

    // ---------------------------------------------------------------- path construction

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        Rectangle2D rect = new Rectangle2D.Double();
        rect.setFrameFromDiagonal(toPage(p0.getX(), p0.getY()), toPage(p2.getX(), p2.getY()));
        pathRects.add(rect);
        addToPath(rect.getMinX(), rect.getMinY());
        addToPath(rect.getMaxX(), rect.getMaxY());
        current = p0;
        subpathStart = p0;
    }

    @Override
    public void moveTo(float x, float y) {
        current = new Point2D.Float(x, y);
        subpathStart = current;
        Point2D p = toPage(x, y);
        addToPath(p.getX(), p.getY());
    }

    @Override
    public void lineTo(float x, float y) {
        Point2D from = toPage(current.getX(), current.getY());
        Point2D to = toPage(x, y);
        pathLines.add(new Point2D[] {from, to});
        if (Math.abs(from.getX() - to.getX()) >= 0.5 && Math.abs(from.getY() - to.getY()) >= 0.5) {
            pathIsDrawing = true;
        }
        addToPath(to.getX(), to.getY());
        current = new Point2D.Float(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        for (Point2D p : List.of(toPage(x1, y1), toPage(x2, y2), toPage(x3, y3))) {
            addToPath(p.getX(), p.getY());
        }
        pathIsDrawing = true;
        current = new Point2D.Float(x3, y3);
    }

    private void addToPath(double x, double y) {
        if (pathBounds == null) {
            pathBounds = new Rectangle2D.Double(x, y, 0, 0);
        } else {
            pathBounds.add(x, y);
        }
    }

    /** Records the outline of the path being painted, as mark or shape. */
    private void recordPainted() {
        if (pathBounds != null) {
            (pathIsDrawing ? marks : shapes).add(pathBounds);
        }
    }

    @Override
    public Point2D getCurrentPoint() {
        return current;
    }

    @Override
    public void closePath() {
        lineTo((float) subpathStart.getX(), (float) subpathStart.getY());
    }

    // ---------------------------------------------------------------- path painting

    @Override
    public void strokePath() {
        recordPainted();
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
        recordPainted();
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
        pathBounds = null;
        pathIsDrawing = false;
    }

    @Override
    public void clip(int windingRule) {
        // The path is discarded afterwards by endPath().
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }

    /** Keeps only horizontal or vertical lines. */
    private void addSegment(double x1, double y1, double x2, double y2) {
        if (Math.abs(y1 - y2) < 0.5 && Math.abs(x1 - x2) >= 0.5) {
            segments.add(new Segment(true, (float) ((y1 + y2) / 2), (float) Math.min(x1, x2), (float) Math.max(x1, x2)));
        } else if (Math.abs(x1 - x2) < 0.5 && Math.abs(y1 - y2) >= 0.5) {
            segments.add(new Segment(false, (float) ((x1 + x2) / 2), (float) Math.min(y1, y2), (float) Math.max(y1, y2)));
        }
    }
}
