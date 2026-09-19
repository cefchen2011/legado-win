package androidx.media3.common;

/**
 * JVM 兼容实现：legado 的 AnalyzeUrl 仅为"在线朗读/音频"构造 MediaItem，
 * 引擎侧只用到 setUri 与 uri 读取，因此保留这一最小面。
 */
public class MediaItem {

    private final String uri;

    private MediaItem(String uri) {
        this.uri = uri;
    }

    public String getUri() {
        return uri;
    }

    @Override
    public String toString() {
        return "MediaItem{uri='" + uri + "'}";
    }

    public static class Builder {
        private String uri;

        public Builder setUri(String uri) {
            this.uri = uri;
            return this;
        }

        public MediaItem build() {
            return new MediaItem(uri);
        }
    }
}
