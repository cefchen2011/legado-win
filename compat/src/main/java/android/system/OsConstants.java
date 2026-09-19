package android.system;

/** JVM 兼容实现：仅提供引擎引用到的常量。 */
public final class OsConstants {
    private OsConstants() {}

    public static final int SEEK_SET = 0;
    public static final int SEEK_CUR = 1;
    public static final int SEEK_END = 2;

    public static final int O_RDONLY = 0;
    public static final int O_WRONLY = 1;
    public static final int O_RDWR = 2;
}
