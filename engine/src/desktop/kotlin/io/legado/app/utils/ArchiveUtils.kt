// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ArchiveUtils 依赖 Android 的 DocumentFile / SAF（存储访问框架）。
// 桌面端直接使用 java.nio 文件系统，因此按上游同名同签名的公开 API 重新实现，
// 解压能力交给 java.util.zip（zip / jar）。
package io.legado.app.utils

import android.net.Uri
import io.legado.app.data.DesktopPaths
import java.io.File
import java.util.zip.ZipInputStream

const val ARCHIVE_TEMP_FOLDER_NAME = "ArchiveTemp"

object ArchiveUtils {

    /** 上游的 ArchiveUtils.TEMP_FOLDER_NAME 是 object 成员，这里保持同样形态 */
    const val TEMP_FOLDER_NAME = ARCHIVE_TEMP_FOLDER_NAME

    private const val TEMP_PATH = TEMP_FOLDER_NAME

    private val tempDir: File get() = File(DesktopPaths.cacheDir, TEMP_FOLDER_NAME).apply { mkdirs() }

    private val archiveExt = setOf(
        "zip", "jar", "cbz", "epub", "apk"
    )

    fun isArchive(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in archiveExt
    }

    /** 上游返回解压出的文件列表；桌面端解压到缓存临时目录。 */
    fun deCompress(
        archivePath: String,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        val src = File(archivePath)
        if (!src.exists()) {
            LogUtils.e("ArchiveUtils", "压缩包不存在: $archivePath")
            return emptyList()
        }
        val outDir = File(DesktopPaths.cacheDir, path).apply { mkdirs() }
        val out = mutableListOf<File>()
        runCatching {
            ZipInputStream(src.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (filter == null || filter(name)) {
                        val target = File(outDir, name)
                        // 防目录穿越
                        if (target.canonicalPath.startsWith(outDir.canonicalPath)) {
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { zis.copyTo(it) }
                                out.add(target)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }.onFailure { LogUtils.e("ArchiveUtils", "解压失败 $archivePath: ${it.message}") }
        return out
    }

    fun deCompress(
        archiveUri: Uri,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> = deCompress(archiveUri.toString(), path, filter)

    /** 列出压缩包内条目名，不做解压。 */
    fun getArchiveFilesName(archivePath: String, filter: ((String) -> Boolean)? = null): List<String> {
        val src = File(archivePath)
        if (!src.exists()) return emptyList()
        val names = mutableListOf<String>()
        runCatching {
            ZipInputStream(src.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (filter == null || filter(entry.name)) names.add(entry.name)
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return names
    }

    fun getArchiveFilesName(uri: Uri, filter: ((String) -> Boolean)? = null): List<String> =
        getArchiveFilesName(uri.toString(), filter)
}
