// [desktop-port] 桌面端替代实现，非上游源码。
//
// Android 的 R 类由 aapt 依据 res/ 生成，R.string.* 是编译期资源 id，
// 运行时由 Resources 查表。桌面端没有这套机制，因此：
//   * R.string.* 仍然是 Int 型 id（保持上游调用签名不变）
//   * 具体文案在 object 初始化时注册进兼容层的 ContextStrings
package io.legado.app

import android.content.ContextStrings

object R {

    object string {
        @JvmField val no_books_dir: Int = 0x7f010001
        @JvmField val replace_rule_invalid: Int = 0x7f010002
        @JvmField val book_source_invalid: Int = 0x7f010003
        @JvmField val net_error: Int = 0x7f010004
        @JvmField val search_book: Int = 0x7f010005
        @JvmField val loading: Int = 0x7f010006
        @JvmField val error_get_web_content: Int = 0x7f010007
        @JvmField val chapter_list_empty: Int = 0x7f010008
        @JvmField val intro_show: Int = 0x7f010009
        @JvmField val intro_show_null: Int = 0x7f01000a
        @JvmField val all: Int = 0x7f01000b
        @JvmField val audio: Int = 0x7f01000c
        @JvmField val local: Int = 0x7f01000d
        @JvmField val video: Int = 0x7f01000e
        @JvmField val net_no_group: Int = 0x7f01000f
        @JvmField val local_no_group: Int = 0x7f010010
        @JvmField val update_book_fail: Int = 0x7f010011

        init {
            ContextStrings.register(no_books_dir, "未找到书籍目录")
            ContextStrings.register(replace_rule_invalid, "替换规则无效")
            ContextStrings.register(book_source_invalid, "书源无效")
            ContextStrings.register(net_error, "网络错误")
            ContextStrings.register(search_book, "搜索书籍")
            ContextStrings.register(loading, "加载中")
            ContextStrings.register(error_get_web_content, "获取网页内容失败：%s")
            ContextStrings.register(chapter_list_empty, "目录为空")
            ContextStrings.register(intro_show, "简介：%s")
            ContextStrings.register(intro_show_null, "暂无简介")
            ContextStrings.register(all, "全部")
            ContextStrings.register(audio, "音频")
            ContextStrings.register(local, "本地")
            ContextStrings.register(video, "视频")
            ContextStrings.register(net_no_group, "未分组")
            ContextStrings.register(local_no_group, "本地未分组")
            ContextStrings.register(update_book_fail, "更新书籍失败")
        }
    }
}
