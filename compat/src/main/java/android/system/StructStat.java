package android.system;

/** JVM 兼容实现：只保留引擎实际读取的字段。 */
public final class StructStat {
    public long st_dev;
    public long st_ino;
    public int st_mode;
    public long st_nlink;
    public int st_uid;
    public int st_gid;
    public long st_rdev;
    public long st_size;
    public long st_blksize;
    public long st_blocks;
    public long st_atime;
    public long st_mtime;
    public long st_ctime;
}
