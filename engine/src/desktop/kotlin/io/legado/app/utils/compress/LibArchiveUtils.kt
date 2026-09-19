// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 LibArchiveUtils 通过 JNI 调用 libarchive 原生库，支持 rar/7z/tar 等格式。
// 桌面端不引入原生依赖，第一阶段降级为 java.util.zip：
//   * zip / cbz / epub / jar 可正常读取
//   * rar / 7z 暂不支持，返回 null 并记录告警
// 保留上游同名同签名的公开 API，调用点零改动。
package io.legado.app.utils.compress

import io.legado.app.utils.LogUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object LibArchiveUtils {

    private const val TAG = "LibArchiveUtils"

    /**
     * 上游：从压缩流中按路径取出条目内容。
     * 桌面端用 ZipInputStream 实现同等能力。
     */
    fun getByteArrayContent(inputStream: InputStream, path: String): ByteArray? {
        val target = path.trimStart('/', '\\')
        return runCatching {
            ZipInputStream(inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.trimStart('/', '\\')
                    if (name == target || name == path) {
                        val out = ByteArrayOutputStream()
                        zis.copyTo(out)
                        return@use out.toByteArray()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                null
            }
        }.getOrElse { e ->
            LogUtils.e(TAG, "读取压缩条目失败 $path: ${e.message}（桌面端暂仅支持 zip 系格式）")
            null
        }
    }
}
