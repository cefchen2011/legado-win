package android.system;

/** JVM 兼容实现：对应 Android 的 android.system.ErrnoException（顶层类）。 */
public class ErrnoException extends Exception {

    public final int errno;

    public ErrnoException(String functionName, int errno) {
        super(functionName + " failed: errno=" + errno);
        this.errno = errno;
    }

    public ErrnoException(String functionName, int errno, Throwable cause) {
        super(functionName + " failed: errno=" + errno, cause);
        this.errno = errno;
    }
}
