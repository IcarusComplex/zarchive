import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a multi-resolution Windows .ico from a source PNG.
 * Each entry is stored as a PNG blob (supported by Windows Vista+),
 * which keeps the file small and preserves transparency.
 *
 * Usage: java MakeIco <input.png> <output.ico>
 */
public class MakeIco {
    static final int[] SIZES = {256, 128, 64, 48, 32, 24, 16};

    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));

        // Pad to a centered square canvas (transparent) so nothing is stretched.
        int side = Math.max(src.getWidth(), src.getHeight());
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, (side - src.getWidth()) / 2, (side - src.getHeight()) / 2, null);
        g.dispose();

        List<byte[]> pngs = new ArrayList<>();
        for (int s : SIZES) {
            BufferedImage scaled = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gs = scaled.createGraphics();
            gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            gs.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            gs.drawImage(square, 0, 0, s, s, null);
            gs.dispose();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", b);
            pngs.add(b.toByteArray());
        }

        int n = SIZES.length;
        int headerSize = 6 + 16 * n;
        int offset = headerSize;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ByteBuffer hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putShort((short) 0);      // reserved
        hdr.putShort((short) 1);      // type = icon
        hdr.putShort((short) n);      // image count
        for (int i = 0; i < n; i++) {
            int s = SIZES[i];
            byte[] png = pngs.get(i);
            hdr.put((byte) (s >= 256 ? 0 : s)); // width  (0 == 256)
            hdr.put((byte) (s >= 256 ? 0 : s)); // height (0 == 256)
            hdr.put((byte) 0);                  // palette count
            hdr.put((byte) 0);                  // reserved
            hdr.putShort((short) 1);            // color planes
            hdr.putShort((short) 32);           // bits per pixel
            hdr.putInt(png.length);             // size of image data
            hdr.putInt(offset);                 // offset of image data
            offset += png.length;
        }
        out.write(hdr.array());
        for (byte[] png : pngs) out.write(png);

        try (FileOutputStream fos = new FileOutputStream(args[1])) {
            fos.write(out.toByteArray());
        }
        System.out.println("Wrote " + args[1] + " (" + out.size() + " bytes, " + n + " sizes)");
    }
}
