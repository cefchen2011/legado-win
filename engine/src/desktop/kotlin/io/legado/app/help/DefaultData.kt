// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 DefaultData 从 Android 的 assets 读取内置默认数据。
// 桌面端把同一份 txtTocRule.json 放到 classpath 资源里，语义完全一致：
// 这些是 legado 打磨过的中文小说分章正则，直接沿用不重写。
package io.legado.app.help

import io.legado.app.data.DesktopJson
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.utils.LogUtils

object DefaultData {

    /** 内置默认分章规则（首次使用时写入数据库） */
    val txtTocRules: List<TxtTocRule> by lazy {
        runCatching {
            val stream = DefaultData::class.java.getResourceAsStream("/defaultData/txtTocRule.json")
                ?: return@runCatching emptyList<TxtTocRule>()
            val json = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, TxtTocRule::class.java
            ).type
            DesktopJson.gson.fromJson<List<TxtTocRule>>(json, type) ?: emptyList()
        }.onFailure {
            LogUtils.e("DefaultData", "加载默认分章规则失败: ${it.message}")
        }.getOrDefault(emptyList())
    }
}
