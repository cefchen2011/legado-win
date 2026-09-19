package com.bumptech.glide.load.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Glide 的图片 URL 包装类。桌面端不引入 Glide，这里提供最小等价物：
 * 引擎只把它当作"带请求头的 URL"使用（toString 取回原始地址）。
 */
public class GlideUrl {

    private final String url;
    private final Map<String, String> headers;

    public GlideUrl(String url) {
        this(url, null);
    }

    public GlideUrl(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? Collections.emptyMap() : new HashMap<>(headers);
    }

    public String toStringUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    @Override
    public String toString() {
        return url;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GlideUrl && url.equals(((GlideUrl) o).url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }
}
