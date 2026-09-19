package androidx.room;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Room 注解的编译期空壳：桌面端不生成数据库代码，仅保留类型以兼容上游源码。 */
@Target({})
@Retention(RetentionPolicy.CLASS)
public @interface ForeignKey {
    Class<?> entity();
    String[] parentColumns();
    String[] childColumns();
    int onDelete() default 1;
    int onUpdate() default 1;
    boolean deferred() default false;
    int NO_ACTION = 1;
    int RESTRICT = 2;
    int SET_NULL = 3;
    int SET_DEFAULT = 4;
    int CASCADE = 5;
}
