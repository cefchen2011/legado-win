package android.util;

import java.nio.charset.StandardCharsets;

/** JVM 兼容实现：委托 java.util.Base64，保留 android 的 flag 常量语义。 */
public final class Base64 {
    private Base64() {}

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private static java.util.Base64.Decoder decoder(int flags) {
        return (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlDecoder()
                : java.util.Base64.getMimeDecoder();
    }

    private static java.util.Base64.Encoder encoder(int flags) {
        if ((flags & URL_SAFE) != 0) return java.util.Base64.getUrlEncoder();
        if ((flags & NO_WRAP) != 0) return java.util.Base64.getEncoder();
        return java.util.Base64.getMimeEncoder(76, new byte[]{'\r', '\n'});
    }

    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        return decoder(flags).decode(input);
    }

    public static byte[] decode(String str) {
        return decode(str, DEFAULT);
    }

    public static String encodeToString(byte[] input, int flags) {
        return encoder(flags).encodeToString(input);
    }

    public static String encodeToString(byte[] input) {
        return encodeToString(input, DEFAULT);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encoder(flags).encode(input);
    }

    public static byte[] encode(byte[] input) {
        return encode(input, DEFAULT);
    }
}
