package android.graphics;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * JVM 兼容实现：桌面端没有 Skia，改用 JDK 自带的 ImageIO。
 * 这是**真实实现**而非空壳——legado 用它把 EPUB 封面转成 JPEG 落盘。
 */
public final class Bitmap {

    private final BufferedImage image;

    Bitmap(BufferedImage image) {
        this.image = image;
    }

    public static Bitmap wrap(BufferedImage image) {
        return new Bitmap(image);
    }

    public BufferedImage unwrap() {
        return image;
    }

    public int getWidth() {
        return image == null ? 0 : image.getWidth();
    }

    public int getHeight() {
        return image == null ? 0 : image.getHeight();
    }

    public boolean isRecycled() {
        return false;
    }

    public void recycle() {
        // Java 有 GC，无需手动回收
    }

    public enum CompressFormat { JPEG, PNG, WEBP }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        if (image == null) return false;
        try {
            String fmt = format == CompressFormat.PNG ? "png" : "jpeg";
            if ("jpeg".equals(fmt)) {
                // JPEG 走 quality 参数；PNG 直接写
                java.util.Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName(fmt);
                if (!it.hasNext()) return ImageIO.write(image, "png", stream);
                ImageWriter writer = it.next();
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(stream)) {
                    writer.setOutput(ios);
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    if (param.canWriteCompressed()) {
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(Math.max(0f, Math.min(1f, quality / 100f)));
                    }
                    // JPEG 不支持 alpha 通道，先转成 RGB
                    BufferedImage rgb = new BufferedImage(
                            image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                    rgb.createGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);
                    writer.write(null, new IIOImage(rgb, null, null), param);
                } finally {
                    writer.dispose();
                }
                return true;
            }
            return ImageIO.write(image, "png", stream);
        } catch (IOException e) {
            return false;
        }
    }

    public static Bitmap createBitmap(int width, int height) {
        return new Bitmap(new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB));
    }
}
