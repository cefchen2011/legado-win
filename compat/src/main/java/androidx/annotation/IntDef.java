package androidx.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 源码级标记注解。
 *
 * 注意：真实 androidx 的 value() 是 long[]，但 Kotlin 用 Int 常量作注解实参时
 * 不会自动加宽为 Long，因此这里改声明为 int[]——本注解 RetentionPolicy 为 SOURCE，
 * 运行期不做任何读取，类型差异不影响语义，却能让上游源码原样编译。
 */
@Target({ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface IntDef {
    int[] value() default {};
    boolean flag() default false;
    boolean open() default false;
}
