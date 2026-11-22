package pdf2odt;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;

/**
 * Wahrnehmungs-Hash (dHash): ändert sich bei inhaltlichen Bildänderungen,
 * aber nicht bei Neukomprimierung oder leichter Skalierung.
 */
final class ImageHash {

    /** Helligkeitsunterschied, unter dem ein Bild als einfarbig gilt. */
    private static final int BLANK_RANGE = 8;

    private ImageHash() {
    }

    /** 64 Bit als 16 Hex-Zeichen. */
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

    /** Einfarbige Bilder (z. B. weiße Flächen als Teil einer Vektorzeichnung) tragen keine Information. */
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

    /** Das Bild auf 9×8 Pixel in Graustufen verkleinert; Transparenz wie auf weißem Papier. */
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
