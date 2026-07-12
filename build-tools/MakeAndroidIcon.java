import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Generates the Android adaptive-icon foreground PNG from the same source art used for the
 * Windows .ico (resources/app_icon.png). The artwork is scaled to ~65% and centered on a
 * transparent square canvas, matching Android's 108dp-canvas / ~66dp-safe-zone adaptive icon
 * spec -- content outside the safe zone can be clipped by circular/squircle OEM masks.
 *
 * Usage: java MakeAndroidIcon <source.png> <output.png> <canvasSize>
 */
public class MakeAndroidIcon {
    static final double SAFE_ZONE_SCALE = 0.65;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java MakeAndroidIcon <source.png> <output.png> <canvasSize>");
            System.exit(1);
        }
        BufferedImage src = ImageIO.read(new File(args[0]));
        int canvas = Integer.parseInt(args[2]);
        int content = (int) Math.round(canvas * SAFE_ZONE_SCALE);
        int offset = (canvas - content) / 2;

        BufferedImage out = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, offset, offset, content, content, null);
        g.dispose();

        ImageIO.write(out, "png", new File(args[1]));
        System.out.println("Wrote " + args[1] + " (" + canvas + "x" + canvas + ", content " + content + "px)");
    }
}
