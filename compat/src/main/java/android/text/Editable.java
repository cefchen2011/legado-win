package android.text;

/**
 * JVM 兼容实现：桌面端没有 Android 的富文本编辑缓冲，
 * 这里用 StringBuilder 承载，满足引擎的实际用法。
 */
public interface Editable extends CharSequence {

    final class Factory {
        private static final Factory INSTANCE = new Factory();

        public static Factory getInstance() {
            return INSTANCE;
        }

        public Editable newEditable(CharSequence source) {
            final StringBuilder sb = new StringBuilder(source == null ? "" : source);
            return new Editable() {
                @Override
                public int length() { return sb.length(); }

                @Override
                public char charAt(int index) { return sb.charAt(index); }

                @Override
                public CharSequence subSequence(int start, int end) {
                    return sb.subSequence(start, end);
                }

                @Override
                public String toString() { return sb.toString(); }
            };
        }
    }
}
