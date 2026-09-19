package kotlinx.parcelize

/**
 * 编译期标记的空壳。
 * legado 的实体类用它标注"不参与 Parcel 序列化"的派生属性；
 * 桌面端不使用 Parcel，因此注解不产生任何代码。
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class IgnoredOnParcel
