// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ACache 是 Android 上的文件缓存（带索引、按目录分桶）。
// 桌面端保留同名同签名的公开 API，实现改为「内存索引 + 文件落盘」：
//   文件内容写入 <cacheDir>/<name>/<hash>.cache
//   索引（key -> 文件名、过期时间）持久化在 index.json
package io.legado.app.utils

import android.content.Context
import io.legado.app.data.DesktopPaths
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ACache private constructor(private val name: String, baseDir: File?) {

    private data class Entry(val file: String, val deadline: Long)

    private val dir: File = (baseDir ?: File(DesktopPaths.cacheDir, name)).apply { mkdirs() }
    private val index = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var indexLoaded = false

    private val indexFile: File get() = File(dir, "index.tsv")

    @Synchronized
    private fun ensureIndex() {
        if (indexLoaded) return
        indexLoaded = true
        if (!indexFile.exists()) return
        runCatching {
            indexFile.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 3) {
                    index[parts[0]] = Entry(parts[1], parts[2].toLongOrNull() ?: 0L)
                }
            }
        }.onFailure { LogUtils.e("ACache", "读取缓存索引失败: ${it.message}") }
    }

    @Synchronized
    private fun saveIndex() {
        runCatching {
            indexFile.writeText(
                index.entries.joinToString("\n") { "${it.key}\t${it.value.file}\t${it.value.deadline}" },
                Charsets.UTF_8
            )
        }.onFailure { LogUtils.e("ACache", "写入缓存索引失败: ${it.message}") }
    }

    private fun fileOf(key: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$hex.cache")
    }

    @Synchronized
    fun put(key: String, value: String) = put(key, value, -1)

    @Synchronized
    fun put(key: String, value: String, saveTime: Int) {
        ensureIndex()
        runCatching {
            val f = fileOf(key)
            f.writeText(value, Charsets.UTF_8)
            val deadline = if (saveTime <= 0) 0L else System.currentTimeMillis() + saveTime * 1000L
            index[key] = Entry(f.name, deadline)
            saveIndex()
        }.onFailure { LogUtils.e("ACache", "写入缓存 $key 失败: ${it.message}") }
    }

    /** 二进制写入（上游 CacheManager.putByteArray 使用） */
    @Synchronized
    fun put(key: String, value: ByteArray, saveTime: Int) {
        ensureIndex()
        runCatching {
            val f = fileOf(key)
            f.writeBytes(value)
            val deadline = if (saveTime <= 0) 0L else System.currentTimeMillis() + saveTime * 1000L
            index[key] = Entry(f.name, deadline)
            saveIndex()
        }.onFailure { LogUtils.e("ACache", "写入二进制缓存 $key 失败: ${it.message}") }
    }

    /** 二进制读取（上游 CacheManager.getByteArray 使用） */
    @Synchronized
    fun getAsBinary(key: String): ByteArray? {
        ensureIndex()
        val entry = index[key] ?: return null
        if (entry.deadline != 0L && entry.deadline < System.currentTimeMillis()) {
            remove(key)
            return null
        }
        val f = File(dir, entry.file)
        if (!f.exists()) {
            index.remove(key)
            saveIndex()
            return null
        }
        return runCatching { f.readBytes() }.getOrNull()
    }

    @Synchronized
    fun getAsString(key: String): String? {
        ensureIndex()
        val entry = index[key] ?: return null
        if (entry.deadline != 0L && entry.deadline < System.currentTimeMillis()) {
            remove(key)
            return null
        }
        val f = File(dir, entry.file)
        if (!f.exists()) {
            index.remove(key)
            saveIndex()
            return null
        }
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    @Synchronized
    fun getAsJSONObject(key: String): org.json.JSONObject? = null

    @Synchronized
    fun getAsJSONArray(key: String): org.json.JSONArray? = null

    @Synchronized
    fun remove(key: String) {
        ensureIndex()
        index.remove(key)?.let { File(dir, it.file).delete() }
        saveIndex()
    }

    @Synchronized
    fun clear() {
        ensureIndex()
        index.values.forEach { File(dir, it.file).delete() }
        index.clear()
        saveIndex()
    }

    fun file(key: String): File {
        ensureIndex()
        val entry = index[key] ?: return fileOf(key)
        return File(dir, entry.file)
    }

    companion object {

        private val instances = ConcurrentHashMap<String, ACache>()

        /** 上游签名：ACache.get() —— 默认缓存实例 */
        fun get(): ACache = get("default")

        /** 上游签名：ACache.get(name: String) */
        fun get(name: String): ACache = instances.computeIfAbsent(name) { ACache(it, null) }

        /** 上游签名：ACache.get(context: Context) —— 桌面端忽略 context */
        fun get(context: Context): ACache = get("default")

        /** 上游签名：ACache.get(context: Context, name: String) */
        fun get(context: Context, name: String): ACache = get(name)

        /** 上游签名：ACache.get(dir: File) —— 直接指定缓存目录（SharedJsScope 使用） */
        fun get(dir: File): ACache =
            instances.computeIfAbsent(dir.absolutePath) { ACache(dir.name, dir) }
    }
}
