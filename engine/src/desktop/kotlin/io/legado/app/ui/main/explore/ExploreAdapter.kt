// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ui/main/explore/ExploreAdapter.Companion.exploreInfoMapList 是发现页的 UI 缓存
// （书源 url -> 发现条目）。桌面端由应用外壳维护同名缓存，引擎只做读写。
package io.legado.app.ui.main.explore

import java.util.concurrent.ConcurrentHashMap

/**
 * 保留上游的 `ExploreAdapter.Companion.exploreInfoMapList` 访问形态，
 * 使 BookSourceExtensions / WebBook 里的 import 与调用点零改动。
 */
class ExploreAdapter {

    companion object {
        /** 对应上游 ExploreAdapter.Companion.exploreInfoMapList */
        @JvmField
        val exploreInfoMapList = ConcurrentHashMap<String, MutableMap<String, String>>()
    }
}
