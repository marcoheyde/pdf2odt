package pdf2odt;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.rendering.PDFRenderer;

import pdf2odt.GraphicsExtractor.PlacedImage;
import pdf2odt.Model.Image;

/**
 * Finds vector drawings (and drawings composed of many small images) on a page so that they can be
 * rendered as one image. Otherwise their boxes would be taken for table grids and their labels for text.
 */
final class FigureDetector {

    /** Marks this close to each other belong to the same drawing (pt). */
    private static final double GAP = 10;
    /** A drawing has at least this many marks ... */
    private static final int MIN_MARKS = 5;
    /** ... and is at least this wide and high (pt). */
    private static final double MIN_SIZE = 40;
    /** Elements touching each other within this distance (pt) belong together, e.g. a line and its arrow head. */
    private static final double TOUCH = 1;
    /** Words this close to a drawing (pt) are labels belonging to it. */
    private static final double LABEL_TOUCH = 3;
    /** Shapes covering at least this share of the page in both directions are backgrounds, not drawings. */
    private static final double BACKGROUND = 0.9;
    private static final float DPI = 150;
    /** Margin around a drawing when cutting it out (pt). */
    private static final double MARGIN = 3;
    private static final double PT_TO_CM = 2.54 / 72;

    private FigureDetector() {
    }

    /**
     * The areas of the drawings. All painted elements are grouped: marks (curves, diagonal lines, images)
     * close to each other, and any elements touching each other (e.g. an arrow line and its arrow head,
     * a box and a line). A group with enough marks is a drawing; tables consist of shapes only and are
     * not affected. The drawing grows by the words touching it (labels); drawings close to each other are
     * merged.
     */
    static List<Rectangle2D> detect(List<Rectangle2D> marks, List<Rectangle2D> shapes, List<Rectangle2D> words,
            double pageWidth, double pageHeight) {
        List<Rectangle2D> elements = new ArrayList<>(marks);
        shapes.stream()
                .filter(s -> s.getWidth() < BACKGROUND * pageWidth || s.getHeight() < BACKGROUND * pageHeight)
                .forEach(elements::add);
        int[] parent = new int[elements.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < elements.size(); i++) {
            Rectangle2D nearMark = expand(elements.get(i), GAP);
            Rectangle2D touching = expand(elements.get(i), TOUCH);
            for (int j = i + 1; j < elements.size(); j++) {
                boolean bothMarks = i < marks.size() && j < marks.size();
                if ((bothMarks ? nearMark : touching).intersects(elements.get(j))) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }

        Map<Integer, Rectangle2D> areas = new HashMap<>();
        Map<Integer, Integer> markCount = new HashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            int root = find(parent, i);
            Rectangle2D element = elements.get(i);
            areas.merge(root, (Rectangle2D) element.clone(), (a, b) -> {
                a.add(b);
                return a;
            });
            if (i < marks.size()) {
                markCount.merge(root, 1, Integer::sum);
            }
        }
        List<Rectangle2D> regions = new ArrayList<>();
        for (Map.Entry<Integer, Rectangle2D> area : areas.entrySet()) {
            Rectangle2D region = area.getValue();
            if (markCount.getOrDefault(area.getKey(), 0) >= MIN_MARKS
                    && region.getWidth() >= MIN_SIZE && region.getHeight() >= MIN_SIZE) {
                while (grow(region, words, LABEL_TOUCH)) {
                    // repeat until no further label touches the drawing
                }
                regions.add(region);
            }
        }
        regions.sort(Comparator.comparingDouble(Rectangle2D::getMinY));
        return mergeOverlapping(regions);
    }

    /** Adds all boxes within {@code distance} of the region to it; true if the region grew. */
    private static boolean grow(Rectangle2D region, List<Rectangle2D> boxes, double distance) {
        boolean grown = false;
        Rectangle2D near = expand(region, distance);
        for (Rectangle2D box : boxes) {
            if (near.intersects(box) && !region.contains(box)) {
                region.add(box);
                grown = true;
            }
        }
        return grown;
    }

    /** Is the point inside one of the regions (with a small margin)? */
    static boolean inside(List<Rectangle2D> regions, double x, double y) {
        return regions.stream().anyMatch(r -> expand(r, 2).contains(x, y));
    }

    /** Renders the page once for cutting out its drawings. */
    static BufferedImage renderPage(PDFRenderer renderer, int pageIndex) throws IOException {
        return renderer.renderImageWithDPI(pageIndex, DPI);
    }

    /** Cuts the region (with a margin for descenders and line widths) out of the rendered page as an image. */
    static PlacedImage cut(BufferedImage page, Rectangle2D drawing) throws IOException {
        Rectangle2D region = expand(drawing, MARGIN);
        double scale = DPI / 72;
        int x = (int) Math.max(0, Math.floor(region.getMinX() * scale));
        int y = (int) Math.max(0, Math.floor(region.getMinY() * scale));
        int w = (int) Math.min(page.getWidth() - x, Math.ceil(region.getWidth() * scale));
        int h = (int) Math.min(page.getHeight() - y, Math.ceil(region.getHeight() * scale));
        BufferedImage figure = page.getSubimage(x, y, w, h);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(figure, "png", png);
        return new PlacedImage(region, new Image(png.toByteArray(), region.getWidth() * PT_TO_CM,
                region.getHeight() * PT_TO_CM, ImageHash.of(figure)));
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Merges regions closer than {@link #GAP} to each other. */
    private static List<Rectangle2D> mergeOverlapping(List<Rectangle2D> regions) {
        List<Rectangle2D> merged = new ArrayList<>();
        for (Rectangle2D region : regions) {
            Rectangle2D current = (Rectangle2D) region.clone();
            for (int i = merged.size() - 1; i >= 0; i--) {
                if (expand(merged.get(i), GAP).intersects(current)) {
                    current.add(merged.remove(i));
                }
            }
            merged.add(current);
        }
        return merged;
    }

    private static Rectangle2D expand(Rectangle2D r, double by) {
        return new Rectangle2D.Double(r.getX() - by, r.getY() - by, r.getWidth() + 2 * by, r.getHeight() + 2 * by);
    }
}
