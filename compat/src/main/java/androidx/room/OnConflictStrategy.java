package androidx.room;

/** Room 冲突策略常量。 */
public @interface OnConflictStrategy {
    int REPLACE = 1;
    int ROLLBACK = 2;
    int ABORT = 3;
    int FAIL = 4;
    int IGNORE = 5;
}
