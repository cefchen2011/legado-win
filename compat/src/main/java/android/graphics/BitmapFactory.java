package android.graphics;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * JVM 兼容实现：基于 ImageIO 解码图片。
 * 支持 png / jpg / gif / bmp；webp 需要额外插件，解码失败时返回 null（上游同样是可空返回）。
 */
public final class BitmapFactory {

    private BitmapFactory() {}

    public static Bitmap decodeStream(InputStream is) {
        if (is == null) return null;
        try {
            BufferedImage img = ImageIO.read(is);
            return img == null ? null : Bitmap.wrap(img);
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeFile(String pathName) {
        try (InputStream in = new FileInputStream(pathName)) {
            return decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeFile(File file) {
        return decodeFile(file.getAbsolutePath());
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        if (data == null) return null;
        try (InputStream in = new java.io.ByteArrayInputStream(data, offset, length)) {
            return decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        return decodeByteArray(data, offset, length);
    }

    /** 上游只用到 inJustDecodeBounds 做尺寸探测；这里保留字段形状 */
    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public int outWidth;
        public int outHeight;
    }
}
