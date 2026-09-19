package android.system;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;

import android.os.ParcelFileDescriptor;

/**
 * JVM 兼容实现：Android 的底层 POSIX 调用。
 *
 * 这里不是空壳——epublib 的 AndroidZipFile 直接依赖 read/lseek/fstat 来解析 zip，
 * 全部实现为对 ParcelFileDescriptor 登记的真实文件句柄操作，
 * 因此 EPUB 的 zip 条目能被正确读出。
 */
public final class Os {

    private Os() {}

    private static long seek(FileDescriptor fd, long offset, int whence) throws ErrnoException {
        RandomAccessFile raf = ParcelFileDescriptor.lookup(fd);
        if (raf == null) throw new ErrnoException("lseek", 9); // EBADF
        try {
            long target;
            if (whence == OsConstants.SEEK_CUR) {
                target = raf.getFilePointer() + offset;
            } else if (whence == OsConstants.SEEK_END) {
                target = raf.length() + offset;
            } else {
                target = offset;
            }
            raf.seek(target);
            return raf.getFilePointer();
        } catch (IOException e) {
            throw new ErrnoException("lseek", 5, e);
        }
    }

    public static long lseek(FileDescriptor fd, long offset, int whence) throws ErrnoException {
        return seek(fd, offset, whence);
    }

    public static int read(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount)
            throws ErrnoException {
        RandomAccessFile raf = ParcelFileDescriptor.lookup(fd);
        if (raf == null) throw new ErrnoException("read", 9);
        try {
            return raf.read(bytes, byteOffset, byteCount);
        } catch (IOException e) {
            throw new ErrnoException("read", 5, e);
        }
    }

    public static int write(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount)
            throws ErrnoException {
        RandomAccessFile raf = ParcelFileDescriptor.lookup(fd);
        if (raf == null) throw new ErrnoException("write", 9);
        try {
            raf.write(bytes, byteOffset, byteCount);
            return byteCount;
        } catch (IOException e) {
            throw new ErrnoException("write", 5, e);
        }
    }

    public static StructStat fstat(FileDescriptor fd) throws ErrnoException {
        StructStat st = new StructStat();
        RandomAccessFile raf = ParcelFileDescriptor.lookup(fd);
        try {
            st.st_size = raf != null ? raf.length() : 0L;
        } catch (IOException e) {
            st.st_size = 0L;
        }
        return st;
    }

    public static StructStat stat(String path) throws ErrnoException {
        StructStat st = new StructStat();
        java.io.File f = new java.io.File(path);
        st.st_size = f.length();
        st.st_mode = f.isDirectory() ? 0x4000 : 0x8000;
        return st;
    }

    /** 注意：ErrnoException 是 android.system 下的顶层类，见 ErrnoException.java */
}
