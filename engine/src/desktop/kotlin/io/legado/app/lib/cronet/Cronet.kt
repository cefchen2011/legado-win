// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 lib/cronet 用 Android 的 Cronet（Chromium 网络栈）作为 OkHttp 的底层引擎。
// 桌面端不引入 Cronet，HttpHelper 回退到标准 OkHttp。
//
// 这里保持与上游 help.http.Cronet.LoaderInterface 一致的接口，
// 使 `Cronet.loader` 的类型推断成立。
package io.legado.app.lib.cronet

import io.legado.app.help.http.Cronet.LoaderInterface
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

object CronetLoader : LoaderInterface {

    /** 桌面端不启用 Cronet */
    const val isAvailable: Boolean = false

    override fun install(): Boolean = false

    override fun preDownload() {
        // no-op
    }
}

/**
 * 上游用 Cronet 引擎构造拦截器；桌面端返回一个**直通拦截器**，
 * 保证插入 OkHttp 后行为与不插入完全一致。
 */
class CronetInterceptor(private val cookieJar: Any? = null) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        return chain.proceed(request)
    }
}
