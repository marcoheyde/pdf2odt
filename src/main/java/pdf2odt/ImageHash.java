package pdf2odt;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;

/**
 * Perceptual hash (dHash): changes when the image content changes,
 * but not on recompression or slight scaling.
 */
final class ImageHash {

    /** Brightness range below which an image counts as single-coloured. */
    private static final int BLANK_RANGE = 8;

    private ImageHash() {
    }

    /** 64 bits as 16 hex characters. */
    static String of(BufferedImage image) {
        Raster small = small(image);
        long bits = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                bits <<= 1;
                if (small.getSample(x, y, 0) > small.getSample(x + 1, y, 0)) {
                    bits |= 1;
                }
            }
        }
        return String.format("%016x", bits);
    }

    /** Single-coloured images (e.g. white areas within a vector drawing) carry no information. */
    static boolean isBlank(BufferedImage image) {
        Raster small = small(image);
        int min = 255;
        int max = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 9; x++) {
                min = Math.min(min, small.getSample(x, y, 0));
                max = Math.max(max, small.getSample(x, y, 0));
            }
        }
        return max - min < BLANK_RANGE;
    }

    /** The image scaled down to 9×8 grey pixels; transparency rendered as on white paper. */
    private static Raster small(BufferedImage image) {
        BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image.getScaledInstance(9, 8, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, Color.WHITE, null);
        g.dispose();
        return small.getRaster();
    }
}
