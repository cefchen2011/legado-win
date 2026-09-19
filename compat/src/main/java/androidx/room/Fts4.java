package androidx.room;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Room 注解的编译期空壳：桌面端不生成数据库代码，仅保留类型以兼容上游源码。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Fts4 {
    String[] tokenizer() default {};
    String[] tokenizerArgs() default {};
    String[] contentEntity() default {};
    String[] languageId() default {};
    String[] matchInfo() default {};
    String[] notIndexed() default {};
    String[] prefix() default {};
    String[] order() default {};
    boolean deferred() default false;
}
