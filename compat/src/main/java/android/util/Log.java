package android.util;

/** JVM 兼容实现：输出到 stdout/stderr，行为接近 android.util.Log。 */
public final class Log {
    private Log() {}

    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private static int println(int priority, String tag, String msg, Throwable tr) {
        String line = "[" + tag + "] " + msg;
        if (priority >= ERROR) System.err.println(line); else System.out.println(line);
        if (tr != null) tr.printStackTrace(priority >= ERROR ? System.err : System.out);
        return 0;
    }

    public static int v(String tag, String msg) { return println(VERBOSE, tag, msg, null); }
    public static int v(String tag, String msg, Throwable tr) { return println(VERBOSE, tag, msg, tr); }
    public static int d(String tag, String msg) { return println(DEBUG, tag, msg, null); }
    public static int d(String tag, String msg, Throwable tr) { return println(DEBUG, tag, msg, tr); }
    public static int i(String tag, String msg) { return println(INFO, tag, msg, null); }
    public static int i(String tag, String msg, Throwable tr) { return println(INFO, tag, msg, tr); }
    public static int w(String tag, String msg) { return println(WARN, tag, msg, null); }
    public static int w(String tag, String msg, Throwable tr) { return println(WARN, tag, msg, tr); }
    public static int e(String tag, String msg) { return println(ERROR, tag, msg, null); }
    public static int e(String tag, String msg, Throwable tr) { return println(ERROR, tag, msg, tr); }
    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
    public static boolean isLoggable(String tag, int level) { return true; }
}
