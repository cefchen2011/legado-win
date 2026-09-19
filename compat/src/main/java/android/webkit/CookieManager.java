package android.webkit;

import java.util.HashMap;
import java.util.Map;

/**
 * JVM 兼容实现：Android 的 WebView Cookie 仓库。
 * 桌面端没有 WebView，这里用进程内的 Cookie 表承载，
 * 语义与上游一致（setCookie / getCookie / removeAllCookies / flush）。
 */
public class CookieManager {

    private static final CookieManager INSTANCE = new CookieManager();

    private final Map<String, String> store = new HashMap<>();
    private boolean acceptCookie = true;

    public static CookieManager getInstance() {
        return INSTANCE;
    }

    public synchronized void setAcceptCookie(boolean accept) {
        this.acceptCookie = accept;
    }

    public synchronized boolean acceptCookie() {
        return acceptCookie;
    }

    public synchronized void setCookie(String url, String value) {
        if (url == null) return;
        store.put(url, value);
    }

    public synchronized String getCookie(String url) {
        return store.get(url);
    }

    public synchronized void removeAllCookies(ValueCallback<Boolean> callback) {
        store.clear();
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }

    public synchronized void removeSessionCookies(ValueCallback<Boolean> callback) {
        if (callback != null) callback.onReceiveValue(Boolean.TRUE);
    }

    public void flush() {
        // 桌面端为进程内存储，无需刷盘
    }

    public interface ValueCallback<T> {
        void onReceiveValue(T value);
    }
}
