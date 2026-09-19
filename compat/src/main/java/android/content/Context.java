package android.content;

import java.io.File;

/**
 * 最小 Context 实现：满足 legado 引擎对应用目录与字符串资源的访问。
 *
 * 桌面端没有 Android 的应用沙箱，这里把数据根目录集中到
 * {@link #setDataRoot(File)}，默认用户主目录下的 .legado-win。
 */
public class Context {

    /**
     * 数据根目录。优先取系统属性 legado.data.root（启动器用 -D 指定），
     * 否则回落到用户主目录下的 .legado-win。
     */
    private static volatile File dataRoot = resolveDefaultRoot();

    private static File resolveDefaultRoot() {
        String configured = System.getProperty("legado.data.root");
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured.trim());
        }
        return new File(System.getProperty("user.home"), ".legado-win");
    }

    public static void setDataRoot(File root) {
        if (root != null) dataRoot = root;
    }

    public static File getDataRoot() {
        return dataRoot;
    }

    public File getCacheDir() {
        return new File(dataRoot, "cache");
    }

    public File getFilesDir() {
        return new File(dataRoot, "files");
    }

    public File getExternalCacheDir() {
        return new File(dataRoot, "external-cache");
    }

    public File getExternalFilesDir(String type) {
        File base = new File(dataRoot, "external-files");
        return type == null ? base : new File(base, type);
    }

    public String getPackageName() {
        return "io.legado.win";
    }

    public Object getSystemService(String name) {
        return null;
    }

    public String getString(int resId) {
        return ContextStrings.get(resId);
    }

    /** 对应 Android 的 getString(int, Object...) 格式化重载 */
    public String getString(int resId, Object... formatArgs) {
        return String.format(ContextStrings.get(resId), formatArgs);
    }

    private static final java.util.Map<String, SharedPreferences> PREFS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return PREFS.computeIfAbsent(name, DesktopSharedPreferences::new);
    }

    public void startActivity(Intent intent) {
        // 桌面端没有 Activity 启动语义，由应用外壳以窗口方式承接
    }
}
