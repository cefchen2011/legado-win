package androidx.room;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Room 注解的编译期空壳：桌面端不生成数据库代码，仅保留类型以兼容上游源码。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Database {
    Class<?>[] entities() default {};
    Class<?>[] views() default {};
    int version() default 1;
    boolean exportSchema() default true;
}
