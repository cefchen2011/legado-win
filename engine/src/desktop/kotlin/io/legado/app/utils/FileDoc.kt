// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 FileDocExtensions.kt 把 Android 的 SAF（DocumentFile）包装成 FileDoc。
// 桌面端文件系统可直接访问，因此这里用 java.nio 实现同名同签名的抽象，
// 使 FileExtensions 等上游代码零改动。
package io.legado.app.utils

import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 上游的文档过滤函数接口 */
fun interface FileDocFilter {
    fun invoke(doc: FileDoc): Boolean
}

class FileDoc(
    val name: String,
    val isDirectory: Boolean,
    val length: Long,
    val lastModified: Long,
    val uri: Uri,
) {

    /** 对应的本地文件 */
    val file: File get() = File(uri.toString().removePrefix("file://"))

    fun exists(): Boolean = file.exists()

    fun canRead(): Boolean = file.canRead()

    fun canWrite(): Boolean = file.canWrite()

    fun delete(): Boolean = file.delete()

    fun listFiles(): List<FileDoc> =
        file.listFiles()?.map { fromFile(it) } ?: emptyList()

    fun findFile(name: String): FileDoc? =
        listFiles().firstOrNull { it.name == name }

    fun createDirectory(name: String): FileDoc? {
        val dir = File(file, name)
        return if (dir.mkdirs() || dir.isDirectory) fromFile(dir) else null
    }

    fun createFile(mimeType: String?, name: String): FileDoc? {
        val f = File(file, name)
        return runCatching {
            f.parentFile?.mkdirs()
            if (f.createNewFile() || f.exists()) fromFile(f) else null
        }.getOrNull()
    }

    fun inputStream(): InputStream = file.inputStream()

    fun outputStream(): OutputStream = file.outputStream()

    companion object {

        fun fromFile(file: File): FileDoc = FileDoc(
            name = file.name,
            isDirectory = file.isDirectory,
            length = file.length(),
            lastModified = file.lastModified(),
            uri = Uri.fromFile(file),
        )

        fun fromUri(uri: Uri, isDir: Boolean = false): FileDoc {
            val f = File(uri.toString().removePrefix("file://"))
            return fromFile(f)
        }
    }
}
