package kotlinx.parcelize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 编译期标记的空壳：桌面端不使用 Parcel，因此注解不产生任何代码。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Parcelize {
}
