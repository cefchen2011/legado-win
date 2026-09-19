package android.os;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;

/**
 * JVM 兼容实现：Android 的可传递文件描述符。
 * 桌面端没有 IPC 传 fd 的需求，直接包装本地文件。
 */
public class ParcelFileDescriptor implements Closeable {

    private final FileDescriptor descriptor;
    private final RandomAccessFile raf;

    /**
     * FileDescriptor -> RandomAccessFile 登记表。
     *
     * 为什么需要它：epublib 的 AndroidZipFile 是直接基于 fd 的 zip 读取器
     * （不是 java.util.zip.ZipFile），它通过 android.system.Os.read/lseek 访问文件。
     * 桌面端没有真正的 fd 层，因此由这里把 fd 映射回可随机访问的文件句柄，
     * 让 Os 的调用能真正落到文件上——否则 zip 条目会读成空。
     */
    public static final Map<FileDescriptor, RandomAccessFile> REGISTRY =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static RandomAccessFile lookup(FileDescriptor fd) {
        return REGISTRY.get(fd);
    }
    private ParcelFileDescriptor(FileDescriptor descriptor, RandomAccessFile raf) {
        this.descriptor = descriptor;
        this.raf = raf;
        if (raf != null) REGISTRY.put(descriptor, raf);
    }

    public static ParcelFileDescriptor open(File file, int mode) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, mode == MODE_READ_ONLY ? "r" : "rw");
        return new ParcelFileDescriptor(raf.getFD(), raf);
    }

    public static ParcelFileDescriptor open(File file) throws IOException {
        return open(file, MODE_READ_ONLY);
    }

    public FileDescriptor getFileDescriptor() {
        return descriptor;
    }

    public long getStatSize() {
        try {
            return raf == null ? -1 : raf.length();
        } catch (IOException e) {
            return -1;
        }
    }

    public FileInputStream createInputStream() {
        return new FileInputStream(descriptor);
    }

    public FileOutputStream createOutputStream() {
        return new FileOutputStream(descriptor);
    }

    public void close() throws IOException {
        if (raf != null) {
            REGISTRY.remove(descriptor);
            raf.close();
        }
    }

    public static final int MODE_READ_ONLY = 0x10000000;
    public static final int MODE_WRITE_ONLY = 0x20000000;
    public static final int MODE_READ_WRITE = 0x30000000;
    public static final int MODE_CREATE = 0x08000000;
    public static final int MODE_TRUNCATE = 0x04000000;
    public static final int MODE_APPEND = 0x02000000;
}
