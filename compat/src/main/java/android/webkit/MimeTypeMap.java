package android.webkit;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * JVM 兼容实现：内置常见扩展名与 MIME 的映射表，
 * 覆盖阅读器实际会遇到的文本/图片/压缩包类型。
 */
public class MimeTypeMap {

    private static final MimeTypeMap INSTANCE = new MimeTypeMap();

    private static final Map<String, String> MIME = new HashMap<>();
    private static final Map<String, String> EXT = new HashMap<>();

    private static void put(String ext, String mime) {
        MIME.put(ext, mime);
        EXT.putIfAbsent(mime, ext);
    }

    static {
        put("txt", "text/plain");
        put("html", "text/html");
        put("htm", "text/html");
        put("xhtml", "application/xhtml+xml");
        put("xml", "text/xml");
        put("json", "application/json");
        put("css", "text/css");
        put("js", "application/javascript");
        put("epub", "application/epub+zip");
        put("pdf", "application/pdf");
        put("zip", "application/zip");
        put("rar", "application/x-rar-compressed");
        put("7z", "application/x-7z-compressed");
        put("png", "image/png");
        put("jpg", "image/jpeg");
        put("jpeg", "image/jpeg");
        put("gif", "image/gif");
        put("webp", "image/webp");
        put("bmp", "image/bmp");
        put("svg", "image/svg+xml");
        put("mp3", "audio/mpeg");
        put("m4a", "audio/mp4");
        put("wav", "audio/wav");
        put("ogg", "audio/ogg");
        put("mp4", "video/mp4");
    }

    public static MimeTypeMap getSingleton() {
        return INSTANCE;
    }

    public static String getFileExtensionFromUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);
        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        if (dot < 0 || dot < slash) return "";
        return url.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public String getMimeTypeFromExtension(String extension) {
        if (extension == null) return null;
        return MIME.get(extension.toLowerCase(Locale.ROOT));
    }

    public String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) return null;
        return EXT.get(mimeType.toLowerCase(Locale.ROOT));
    }

    public boolean hasExtension(String extension) {
        return extension != null && MIME.containsKey(extension.toLowerCase(Locale.ROOT));
    }

    public boolean hasMimeType(String mimeType) {
        return mimeType != null && EXT.containsKey(mimeType.toLowerCase(Locale.ROOT));
    }
}
