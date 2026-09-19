// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 help/glide 基于 Glide 做图片加载与下载进度回调。
// 桌面端图片由 Web UI 直接加载，因此这里只保留引擎调用点需要的形状。
package io.legado.app.help.glide

/**
 * 上游 GlideHeaders 是 Glide 的 LazyHeaders 子类；
 * 桌面端直接就是一个 header map，语义一致。
 */
class GlideHeaders(headerMap: Map<String, String>) : HashMap<String, String>(headerMap)
