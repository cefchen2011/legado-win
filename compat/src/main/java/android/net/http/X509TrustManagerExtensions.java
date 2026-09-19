package android.net.http;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.X509TrustManager;

/**
 * JVM 兼容实现：Android 用于在自定义 TrustManager 上做主机名校验的辅助类。
 * 桌面端直接委托底层 X509TrustManager。
 */
public class X509TrustManagerExtensions {

    private final X509TrustManager trustManager;

    public X509TrustManagerExtensions(X509TrustManager trustManager) {
        if (trustManager == null) throw new IllegalArgumentException("trustManager == null");
        this.trustManager = trustManager;
    }

    public List<X509Certificate> checkServerTrusted(
            X509Certificate[] chain, String authType, String host) throws CertificateException {
        trustManager.checkServerTrusted(chain, authType);
        return Arrays.asList(chain);
    }

    public boolean isUserAddedCertificate(X509Certificate cert) {
        return false;
    }

    public boolean isSameTrustConfiguration(String hostname1, String hostname2) {
        return true;
    }
}
