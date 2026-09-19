package androidx.room;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Room 注解的编译期空壳：桌面端不生成数据库代码，仅保留类型以兼容上游源码。 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.CLASS)
public @interface ColumnInfo {
    String name() default "[value-unspecified]";
    boolean index() default false;
    boolean unique() default false;
    int collate() default 1;
    String defaultValue() default "[value-unspecified]";
}
