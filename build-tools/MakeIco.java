import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a multi-resolution Windows .ico from two source PNGs.
 * Each entry is stored as a PNG blob (supported by Windows Vista+).
 *
 * Usage: java MakeIco <main.png> <small.png> <output.ico>
 *   main.png  — used for sizes 256, 128, 64, 48, 32, 24
 *   small.png — used for size 16 only (optimised low-res artwork)
 */
public class MakeIco {
    static final int[] MAIN_SIZES  = {256, 128, 64, 48, 32, 24};
    static final int[] SMALL_SIZES = {16};

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java MakeIco <main.png> <small.png> <output.ico>");
            System.exit(1);
        }

        BufferedImage mainSq  = toSquare(ImageIO.read(new File(args[0])));
        BufferedImage smallSq = toSquare(ImageIO.read(new File(args[1])));

        List<int[]>  sizeList = new ArrayList<>();
        List<byte[]> pngList  = new ArrayList<>();

        for (int s : MAIN_SIZES)  { sizeList.add(new int[]{s}); pngList.add(scaledPng(mainSq,  s)); }
        for (int s : SMALL_SIZES) { sizeList.add(new int[]{s}); pngList.add(scaledPng(smallSq, s)); }

        int n          = pngList.size();
        int headerSize = 6 + 16 * n;
        int offset     = headerSize;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer hdr = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putShort((short) 0); // reserved
        hdr.putShort((short) 1); // type = icon
        hdr.putShort((short) n); // image count

        for (int i = 0; i < n; i++) {
            int    s   = sizeList.get(i)[0];
            byte[] png = pngList.get(i);
            hdr.put((byte) (s >= 256 ? 0 : s)); // width  (0 == 256)
            hdr.put((byte) (s >= 256 ? 0 : s)); // height (0 == 256)
            hdr.put((byte) 0);                  // palette count
            hdr.put((byte) 0);                  // reserved
            hdr.putShort((short) 1);            // colour planes
            hdr.putShort((short) 32);           // bits per pixel
            hdr.putInt(png.length);             // size of image data
            hdr.putInt(offset);                 // offset of image data
            offset += png.length;
        }
        out.write(hdr.array());
        for (byte[] png : pngList) out.write(png);

        try (FileOutputStream fos = new FileOutputStream(args[2])) {
            fos.write(out.toByteArray());
        }
        System.out.println("Wrote " + args[2] + " (" + out.size() + " bytes, " + n + " sizes)");
    }

    private static BufferedImage toSquare(BufferedImage src) {
        int side = Math.max(src.getWidth(), src.getHeight());
        BufferedImage sq = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sq.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, (side - src.getWidth()) / 2, (side - src.getHeight()) / 2, null);
        g.dispose();
        return sq;
    }

    private static byte[] scaledPng(BufferedImage src, int size) throws Exception {
        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", b);
        return b.toByteArray();
    }
}
