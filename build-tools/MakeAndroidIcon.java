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
 * Usage: java MakeAndroidIcon <source.png> <output.png> <canvasSize> [scale]
 *   scale defaults to 0.65 (adaptive-icon safe zone); pass 1.0 for a full-bleed inline logo.
 */
public class MakeAndroidIcon {
    static final double SAFE_ZONE_SCALE = 0.65;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java MakeAndroidIcon <source.png> <output.png> <canvasSize> [scale]");
            System.exit(1);
        }
        BufferedImage src = ImageIO.read(new File(args[0]));
        int canvas = Integer.parseInt(args[2]);
        double scale = args.length >= 4 ? Double.parseDouble(args[3]) : SAFE_ZONE_SCALE;
        int content = (int) Math.round(canvas * scale);
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
