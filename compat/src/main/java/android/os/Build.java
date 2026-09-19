package android.os;

/** JVM 兼容实现：固定报告一个足够新的 API 等级，使引擎走桌面等价分支。 */
public final class Build {
    private Build() {}

    public static final String MODEL = "Windows";
    public static final String MANUFACTURER = "Microsoft";
    public static final String BRAND = "Windows";
    public static final String DEVICE = "pc";
    public static final String PRODUCT = "legado-win";

    public static class VERSION {
        /** 对应 Android 14 (API 34)：桌面端视为现代设备。 */
        public static final int SDK_INT = 34;
        public static final String RELEASE = "14";
    }

    public static class VERSION_CODES {
        public static final int LOLLIPOP = 21;
        public static final int M = 23;
        public static final int N = 24;
        public static final int O = 26;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;
    }
}
