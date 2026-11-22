package pdf2odt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

import org.junit.jupiter.api.Test;

class ImageHashTest {

    @Test
    void recompressionKeepsHash() throws Exception {
        BufferedImage original = diagram("Client", "Server");
        assertEquals(ImageHash.of(original), ImageHash.of(jpeg(original, 0.5f)));
    }

    @Test
    void changedContentChangesHash() {
        assertNotEquals(ImageHash.of(diagram("Client", "Server")), ImageHash.of(diagram("Client", null)));
    }

    /** Ein einfaches Diagramm mit zwei Kästen; ohne zweiten Namen fehlt der rechte Kasten. */
    private static BufferedImage diagram(String left, String right) {
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 200);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.fillRect(20, 50, 140, 100);
        g.setColor(Color.WHITE);
        g.drawString(left, 40, 105);
        if (right != null) {
            g.setColor(Color.DARK_GRAY);
            g.fillRect(240, 50, 140, 100);
            g.setColor(Color.WHITE);
            g.drawString(right, 260, 105);
        }
        g.dispose();
        return image;
    }

    private static BufferedImage jpeg(BufferedImage image, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        }
        writer.dispose();
        return ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
    }
}
