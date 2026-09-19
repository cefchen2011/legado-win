package android.os;

import java.io.File;

/**
 * JVM 兼容实现：Android 的外部存储目录在桌面端映射到用户目录。
 */
public final class Environment {

    private Environment() {}

    /** 桌面端把"外部存储"映射到用户主目录，便于与 Android 端配置语义对齐 */
    public static File getExternalStorageDirectory() {
        return new File(System.getProperty("user.home"));
    }

    public static String getExternalStorageState() {
        return MEDIA_MOUNTED;
    }

    public static boolean isExternalStorageRemovable() {
        return false;
    }

    public static File getDataDirectory() {
        return new File(System.getProperty("user.home"));
    }

    public static File getDownloadCacheDirectory() {
        return new File(System.getProperty("java.io.tmpdir"));
    }

    public static final String MEDIA_MOUNTED = "mounted";
    public static final String MEDIA_UNMOUNTED = "unmounted";
    public static final String MEDIA_REMOVED = "removed";
    public static final String MEDIA_BAD_REMOVAL = "bad_removal";
    public static final String MEDIA_CHECKING = "checking";
    public static final String MEDIA_SHARED = "shared";
    public static final String MEDIA_MOUNTED_READ_ONLY = "mounted_ro";
    public static final String MEDIA_UNKNOWN = "unknown";
}
