package android.os;

/**
 * 空标记接口：legado 的实体类实现 Parcelable 仅用于 Android 的 IPC/状态保存。
 * 桌面端不需要序列化到 Parcel，因此这里保留类型但不要求实现任何方法，
 * 使上游实体类可以原样编译。
 */
public interface Parcelable {
}
