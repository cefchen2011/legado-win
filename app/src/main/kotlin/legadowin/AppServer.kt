package legadowin

import com.google.gson.Gson
import io.legado.app.data.DesktopJson
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.DictRule
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.constant.AppConst
import io.legado.app.help.CrashHandler
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.model.Debug
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.rss.Rss
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.data.entities.Server
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * legado Windows 应用外壳。
 *
 * 架构：本进程内嵌 HTTP 服务 + Web UI。引擎（规则解析 / 网络 / 书籍抓取）
 * 直接跑在 JVM 里，前端通过 JSON API 调用，不需要额外的运行时。
 */
class AppServer(private val port: Int) {

    // 用统一配置的 Gson：legado 实体带 java.time 字段，
    // 默认实例在 JDK 9+ 上会因为反射受限而直接失败（详见 DesktopJson 注释）
    private val gson: Gson = DesktopJson.gson
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    private val pool = Executors.newFixedThreadPool(16) { r ->
        Thread(r, "legado-win-http").apply { isDaemon = true }
    }

    fun start() {
        CrashHandler.install()
        server.executor = pool
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        LogUtils.logDeviceInfo()
        println()
        println("=".repeat(60))
        println("  legado 阅读 · Windows 版")
        println("  已启动：http://127.0.0.1:$port")
        println("  数据目录：${io.legado.app.data.DesktopPaths.root}")
        println("=".repeat(60))
        println("按 Ctrl+C 退出")
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    // ------------------------------------------------------------ 路由
    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        try {
            when {
                path.startsWith("/api/") -> handleApi(ex, path.removePrefix("/api/"))
                else -> serveStatic(ex, path)
            }
        } catch (e: Exception) {
            LogUtils.e("AppServer", "处理 $path 失败: ${e.message}", e)
            runCatching { json(ex, 500, mapOf("ok" to false, "error" to e.message)) }
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun handleApi(ex: HttpExchange, api: String) {
        val q = query(ex)
        when (api) {
            // ------------------------------------------------ 书源
            "sources" -> when (ex.requestMethod) {
                "GET" -> {
                    val list = appDb.bookSourceDao.all()
                        .sortedByDescending { it.enabled }
                        .map { sourceBrief(it) }
                    json(ex, 200, mapOf("ok" to true, "total" to list.size, "sources" to list))
                }

                "POST" -> {
                    val body = readBody(ex)
                    val imported = importSources(body)
                    json(ex, 200, mapOf("ok" to true, "imported" to imported))
                }

                "DELETE" -> {
                    val url = q["url"] ?: ""
                    appDb.bookSourceDao.getBookSource(url)?.let { appDb.bookSourceDao.delete(it) }
                    json(ex, 200, mapOf("ok" to true))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "source" -> {
                val url = q["url"] ?: ""
                val src = appDb.bookSourceDao.getBookSource(url)
                if (src == null) json(ex, 404, mapOf("ok" to false, "error" to "书源不存在"))
                else json(ex, 200, mapOf("ok" to true, "source" to src))
            }

            "source/toggle" -> {
                val url = q["url"] ?: ""
                val enabled = q["enabled"]?.toBooleanStrictOrNull() ?: true
                val src = appDb.bookSourceDao.getBookSource(url)
                if (src != null) {
                    src.enabled = enabled
                    appDb.bookSourceDao.update(src)
                }
                json(ex, 200, mapOf("ok" to true))
            }

            // ------------------------------------------------ 搜索
            "search" -> {
                val key = q["key"].orEmpty().trim()
                if (key.isEmpty()) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少搜索关键词"))
                    return
                }
                val onlyUrl = q["source"]
                val onlyGroup = q["group"]
                val sources = appDb.bookSourceDao.enabled()
                    .filter { onlyUrl == null || it.bookSourceUrl == onlyUrl }
                    .filter { onlyGroup == null || (it.bookSourceGroup ?: "默认") == onlyGroup }
                    .filter { onlyUrl != null || !it.searchUrl.isNullOrBlank() }
                    .take(
                        q["limit"]?.toIntOrNull()?.coerceIn(1, 2000)
                            ?: if (onlyUrl != null || onlyGroup != null) 2000 else maxSearchSources
                    )
                if (sources.isEmpty()) {
                    json(ex, 200, mapOf("ok" to true, "books" to emptyList<Any>(), "message" to "没有启用的书源"))
                    return
                }
                val started = System.currentTimeMillis()
                val results = searchAll(sources, key)
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "key" to key,
                        "sourceCount" to sources.size,
                        "okSources" to results.okSources,
                        "failed" to results.failed,
                        "elapsedMs" to (System.currentTimeMillis() - started),
                        "books" to results.books
                    )
                )
            }

            // ------------------------------------------------ 书籍
            "bookinfo" -> {
                val source = appDb.bookSourceDao.getBookSource(q["sourceUrl"] ?: "")
                if (source == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书源不存在"))
                    return
                }
                val book = buildBook(q)
                val info = runBlocking { WebBook.getBookInfoAwait(source, book) }
                json(ex, 200, mapOf("ok" to true, "book" to info))
            }

            "toc" -> {
                val source = appDb.bookSourceDao.getBookSource(q["sourceUrl"] ?: "")
                if (source == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书源不存在"))
                    return
                }
                val book = buildBook(q)
                // 与 legado 真实流程一致：目录页地址来自详情规则。
                // 客户端未提供 tocUrl 时，先取一次详情把它解析出来。
                if (q["tocUrl"].isNullOrBlank()) {
                    runCatching { runBlocking { WebBook.getBookInfoAwait(source, book) } }
                        .onFailure { LogUtils.w("AppServer", "取详情失败（继续用书址当目录页）: ${it.message}") }
                }
                if (book.tocUrl.isNullOrBlank()) book.tocUrl = book.bookUrl
                val result = runBlocking { WebBook.getChapterListAwait(source, book) }
                result.fold(
                    onSuccess = { chapters ->
                        // 目录抓取后落库，便于离线阅读与进度记录
                        appDb.bookChapterDao.delete(book.bookUrl)
                        appDb.bookChapterDao.insert(*chapters.toTypedArray())
                        // 顺带把详情补回书架，下次打开无需重复请求。
                        // 注意：这里失败必须留痕，否则会出现"能看但重启就没了"的怪现象。
                        runCatching { appDb.bookDao.insert(book) }
                            .onFailure { LogUtils.e("AppServer", "写入书架失败：${it.message}", it) }
                        json(
                            ex, 200, mapOf(
                                "ok" to true,
                                "total" to chapters.size,
                                "tocUrl" to book.tocUrl,
                                "chapters" to chapters.map { ch ->
                                    mapOf("index" to ch.index, "title" to ch.title, "url" to ch.url)
                                }
                            )
                        )
                    },
                    onFailure = { e ->
                        json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "目录获取失败")))
                    }
                )
            }

            "content" -> {
                val source = appDb.bookSourceDao.getBookSource(q["sourceUrl"] ?: "")
                if (source == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书源不存在"))
                    return
                }
                val book = buildBook(q)
                val index = q["index"]?.toIntOrNull() ?: 0
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                val chapter = chapters.getOrNull(index)
                if (chapter == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "章节不存在，请先获取目录"))
                    return
                }
                val nextUrl = chapters.getOrNull(index + 1)?.url
                val content = runBlocking {
                    WebBook.getContentAwait(source, book, chapter, nextUrl)
                }
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "index" to index,
                        "total" to chapters.size,
                        "title" to chapter.title,
                        "content" to content,
                        "prevIndex" to if (index > 0) index - 1 else -1,
                        "nextIndex" to if (index < chapters.size - 1) index + 1 else -1
                    )
                )
            }

            // ------------------------------------------------ 书架
            "shelf" -> when (ex.requestMethod) {
                "GET" -> {
                    val books = appDb.bookDao.all().map { bookBrief(it) }
                    json(ex, 200, mapOf("ok" to true, "books" to books))
                }

                "POST" -> {
                    val body = readBody(ex)
                    val book = gson.fromJson(body, Book::class.java)
                    if (book != null) appDb.bookDao.insert(book)
                    json(ex, 200, mapOf("ok" to true))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "shelf/delete" -> {
                val url = q["url"] ?: ""
                appDb.bookDao.getBook(url)?.let { appDb.bookDao.delete(it) }
                appDb.bookChapterDao.delete(url)
                json(ex, 200, mapOf("ok" to true))
            }

            // ------------------------------------------------ 阅读进度
            "progress" -> {
                val bookUrl = q["bookUrl"] ?: ""
                val book = appDb.bookDao.getBook(bookUrl)
                if (book == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书不在书架中，无法记录进度"))
                    return
                }
                when (ex.requestMethod) {
                    "GET" -> json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "bookUrl" to book.bookUrl,
                            "index" to book.durChapterIndex,
                            "title" to book.durChapterTitle,
                            "total" to book.totalChapterNum,
                        )
                    )

                    "POST" -> {
                        q["index"]?.toIntOrNull()?.let { book.durChapterIndex = it }
                        q["title"]?.let { book.durChapterTitle = it }
                        q["total"]?.toIntOrNull()?.let { book.totalChapterNum = it }
                        appDb.bookDao.update(book)
                        // 顺带累计阅读时长（客户端传本次阅读的毫秒数）
                        q["readMs"]?.toLongOrNull()?.let { ms ->
                            if (ms > 0) {
                                val deviceId = AppConst.androidId
                                val rec = appDb.readRecordDao.get(deviceId, book.name)
                                    ?: ReadRecord(deviceId = deviceId, bookName = book.name)
                                rec.readTime += ms
                                rec.lastRead = System.currentTimeMillis()
                                appDb.readRecordDao.update(rec)
                            }
                        }
                        json(ex, 200, mapOf("ok" to true, "index" to book.durChapterIndex))
                    }

                    else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
                }
            }

            // ------------------------------------------------ 阅读设置
            // 复用引擎里移植过来的 ReadBookConfig（上游同名配置对象）
            "config" -> when (ex.requestMethod) {
                "GET" -> json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "config" to ReadBookConfig.durConfig.toMap(),
                    )
                )

                "POST" -> {
                    val body = readBody(ex)
                    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    if (obj == null) {
                        json(ex, 400, mapOf("ok" to false, "error" to "请求体必须是 JSON 对象"))
                        return
                    }
                    obj.get("paragraphIndent")?.let { if (!it.isJsonNull) ReadBookConfig.paragraphIndent = it.asString }
                    obj.get("readBodyToLh")?.let { if (!it.isJsonNull) ReadBookConfig.readBodyToLh = it.asBoolean }
                    obj.get("useZhLayout")?.let { if (!it.isJsonNull) ReadBookConfig.useZhLayout = it.asBoolean }
                    obj.get("textFullJustify")?.let { if (!it.isJsonNull) ReadBookConfig.textFullJustify = it.asBoolean }
                    obj.get("textBottomJustify")?.let { if (!it.isJsonNull) ReadBookConfig.textBottomJustify = it.asBoolean }
                    obj.get("titleMode")?.let { if (!it.isJsonNull) ReadBookConfig.titleMode = it.asInt }
                    obj.get("fontSize")?.let { if (!it.isJsonNull) ReadBookConfig.fontSize = it.asInt }
                    obj.get("lineSpacingExtra")?.let { if (!it.isJsonNull) ReadBookConfig.lineSpacingExtra = it.asInt }
                    obj.get("paddingTop")?.let { if (!it.isJsonNull) ReadBookConfig.paddingTop = it.asInt }
                    obj.get("paddingBottom")?.let { if (!it.isJsonNull) ReadBookConfig.paddingBottom = it.asInt }
                    LogUtils.i("AppServer", "阅读设置已更新")
                    json(ex, 200, mapOf("ok" to true, "config" to ReadBookConfig.durConfig.toMap()))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            // ------------------------------------------------ 发现
            "explore" -> {
                val source = appDb.bookSourceDao.getBookSource(q["sourceUrl"] ?: "")
                if (source == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书源不存在"))
                    return
                }
                val kinds = parseExploreKinds(source.exploreUrl)
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "sourceName" to source.bookSourceName,
                        "kinds" to kinds,
                    )
                )
            }

            "explore/books" -> {
                val source = appDb.bookSourceDao.getBookSource(q["sourceUrl"] ?: "")
                if (source == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书源不存在"))
                    return
                }
                val url = q["url"] ?: ""
                if (url.isBlank()) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少发现分类地址"))
                    return
                }
                val page = q["page"]?.toIntOrNull() ?: 1
                val started = System.currentTimeMillis()
                val books = try {
                    runBlocking {
                        withTimeout(searchTimeoutMs) { WebBook.exploreBookAwait(source, url, page) }
                    }
                } catch (e: Exception) {
                    val reason = if (e is kotlinx.coroutines.TimeoutCancellationException)
                        "超时（${searchTimeoutMs / 1000}s）" else (e.message ?: e.javaClass.simpleName)
                    json(ex, 200, mapOf("ok" to false, "error" to reason))
                    return
                }
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "page" to page,
                        "elapsedMs" to (System.currentTimeMillis() - started),
                        "books" to books.map { sb ->
                            mapOf(
                                "name" to sb.name,
                                "author" to sb.author,
                                "kind" to sb.kind,
                                "intro" to sb.intro,
                                "coverUrl" to sb.coverUrl,
                                "bookUrl" to sb.bookUrl,
                                "origin" to sb.origin,
                                "originName" to sb.originName,
                                "latestChapterTitle" to sb.latestChapterTitle,
                                "wordCount" to sb.wordCount,
                                "variableMap" to sb.variableMap,
                            )
                        },
                    )
                )
            }

            // ------------------------------------------------ 书源导出
            /**
             * 导出整本书为 TXT。
             *
             * 在线书逐章抓取（走 `ruleContent`），本地书直接读源文件章节。
             * 抓不到的章节会留一行说明而不是中断整次导出——长篇连载常有几章失效，
             * 为此丢掉整本书不值得。
             */
            "export" -> {
                val bookUrl = q["bookUrl"] ?: ""
                val book = appDb.bookDao.getBook(bookUrl)
                if (book == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书不在书架中"))
                    return
                }
                val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
                if (chapters.isEmpty()) {
                    json(ex, 200, mapOf("ok" to false, "error" to "还没有目录，请先打开这本书"))
                    return
                }
                val from = q["from"]?.toIntOrNull()?.coerceIn(0, chapters.size - 1) ?: 0
                val to = q["to"]?.toIntOrNull()?.coerceIn(from, chapters.size - 1)
                    ?: (chapters.size - 1)
                val isLocal = book.isLocal

                val sb = StringBuilder()
                sb.append(book.name).append('\n')
                if (!book.author.isNullOrBlank()) sb.append("作者：").append(book.author).append('\n')
                sb.append("来源：").append(book.originName.ifBlank { book.origin }).append('\n')
                sb.append("导出章节：第 ").append(from + 1).append(" - ").append(to + 1)
                    .append(" 章，共 ").append(chapters.size).append(" 章\n")
                sb.append("=".repeat(40)).append("\n\n")

                var okCount = 0
                var failCount = 0
                for (i in from..to) {
                    val ch = chapters[i]
                    sb.append('\n').append(ch.title ?: "第 ${i + 1} 章").append("\n\n")
                    val content = runCatching {
                        if (isLocal) {
                            if (book.isEpub) EpubFile.getContent(book, ch)
                            else TextFile.getContent(book, ch)
                        } else {
                            val src = appDb.bookSourceDao.getBookSource(book.origin)
                            if (src == null) error("书源不存在")
                            runBlocking {
                                withTimeout(searchTimeoutMs) {
                                    WebBook.getContentAwait(src, book, ch, chapters.getOrNull(i + 1)?.url, false)
                                }
                            }
                        }
                    }.getOrElse { e ->
                        failCount++
                        "【本章抓取失败：${e.message ?: e.javaClass.simpleName}】"
                    }
                    sb.append(content?.trim().orEmpty()).append("\n")
                    if (!content.isNullOrBlank() && !content.startsWith("【本章抓取失败")) okCount++
                }

                val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
                val safeName = book.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
                // HTTP 头只能放 ASCII：filename= 给回退名，真正的中文名放 filename*=
                val encoded = java.net.URLEncoder.encode("$safeName.txt", "UTF-8")
                    .replace("+", "%20")
                ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                ex.responseHeaders.add(
                    "Content-Disposition",
                    "attachment; filename=\"book.txt\"; filename*=UTF-8''$encoded"
                )
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                LogUtils.i(
                    "AppServer",
                    "导出《${book.name}》第 ${from + 1}-${to + 1} 章：成功 $okCount，失败 $failCount"
                )
            }

            "sources/export" -> {
                val all = appDb.bookSourceDao.all()
                val bytes = gson.toJson(all).toByteArray(StandardCharsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.responseHeaders.add(
                    "Content-Disposition",
                    "attachment; filename=\"legado-book-sources.json\""
                )
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }

            // ------------------------------------------------ 替换净化规则
            //
            // legado 的"净化"能力：按正则替换正文里的广告/水印/多余字符。
            // 引擎侧 ContentProcessor 取正文时会自动应用启用的规则，
            // 这里只负责规则的增删改查。
            "replace" -> when (ex.requestMethod) {
                "GET" -> {
                    val list = appDb.replaceRuleDao.all().sortedBy { it.order }
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "total" to list.size,
                            "enabledCount" to list.count { it.isEnabled },
                            "rules" to list.map { replaceBrief(it) },
                        )
                    )
                }

                "POST" -> {
                    val body = readBody(ex)
                    val rule = runCatching { gson.fromJson(body, ReplaceRule::class.java) }.getOrNull()
                    if (rule == null || rule.pattern.isBlank()) {
                        json(ex, 400, mapOf("ok" to false, "error" to "规则内容不能为空"))
                        return
                    }
                    if (rule.name.isBlank()) rule.name = rule.pattern.take(20)
                    appDb.replaceRuleDao.update(rule)
                    json(ex, 200, mapOf("ok" to true, "rule" to replaceBrief(rule)))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "replace/delete" -> {
                val id = q["id"]?.toLongOrNull()
                if (id == null) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少规则 id"))
                    return
                }
                appDb.replaceRuleDao.all().firstOrNull { it.id == id }
                    ?.let { appDb.replaceRuleDao.delete(it) }
                json(ex, 200, mapOf("ok" to true))
            }

            "replace/toggle" -> {
                val id = q["id"]?.toLongOrNull()
                val enabled = q["enabled"]?.toBooleanStrictOrNull() ?: true
                val rule = appDb.replaceRuleDao.all().firstOrNull { it.id == id }
                if (rule == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "规则不存在"))
                    return
                }
                rule.isEnabled = enabled
                appDb.replaceRuleDao.update(rule)
                json(ex, 200, mapOf("ok" to true))
            }

            // ------------------------------------------------ 书签
            "bookmark" -> when (ex.requestMethod) {
                "GET" -> {
                    val name = q["name"] ?: ""
                    val author = q["author"] ?: ""
                    val list = if (name.isBlank()) appDb.bookmarkDao.all()
                    else appDb.bookmarkDao.getByBook(name, author)
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "total" to list.size,
                            "bookmarks" to list.map { bookmarkBrief(it) },
                        )
                    )
                }

                "POST" -> {
                    val body = readBody(ex)
                    val bm = runCatching { gson.fromJson(body, Bookmark::class.java) }.getOrNull()
                    if (bm == null) {
                        json(ex, 400, mapOf("ok" to false, "error" to "书签内容不合法"))
                        return
                    }
                    appDb.bookmarkDao.insert(bm)
                    json(ex, 200, mapOf("ok" to true, "bookmark" to bookmarkBrief(bm)))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "bookmark/delete" -> {
                val time = q["time"]?.toLongOrNull()
                if (time == null) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少书签 id"))
                    return
                }
                appDb.bookmarkDao.all().firstOrNull { it.time == time }
                    ?.let { appDb.bookmarkDao.delete(it) }
                json(ex, 200, mapOf("ok" to true))
            }

            // ------------------------------------------------ 阅读历史
            "history" -> {
                val deviceId = AppConst.androidId
                val records = appDb.readRecordDao.getByDevice(deviceId)
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "total" to records.size,
                        "records" to records.map {
                            mapOf(
                                "bookName" to it.bookName,
                                "readTime" to it.readTime,
                                "lastRead" to it.lastRead,
                                "readTimeText" to formatDuration(it.readTime),
                            )
                        },
                    )
                )
            }

            // ------------------------------------------------ 书架分组
            "groups" -> when (ex.requestMethod) {
                "GET" -> {
                    val books = appDb.bookDao.all()
                    val groups = appDb.bookGroupDao.all().sortedBy { it.order }
                    val list = groups.map { g ->
                        mapOf(
                            "groupId" to g.groupId,
                            "groupName" to g.groupName,
                            "order" to g.order,
                            "bookCount" to books.count { it.group == g.groupId },
                        )
                    }
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "total" to groups.size,
                            // 未分组数量：groupId 为 0 的书
                            "ungrouped" to books.count { it.group == 0L },
                            "groups" to list,
                        )
                    )
                }

                "POST" -> {
                    val body = readBody(ex)
                    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    val name = obj?.get("groupName")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    if (name.isNullOrBlank()) {
                        json(ex, 400, mapOf("ok" to false, "error" to "分组名不能为空"))
                        return
                    }
                    // 支持改名：传了 groupId 就更新，否则新建
                    val id = obj.get("groupId")?.takeIf { !it.isJsonNull }?.asLong
                    val group = if (id != null) {
                        appDb.bookGroupDao.getByID(id) ?: BookGroup(groupId = id)
                    } else {
                        BookGroup(groupId = System.currentTimeMillis())
                    }
                    group.groupName = name
                    obj.get("order")?.takeIf { !it.isJsonNull }?.let { group.order = it.asInt }
                    // 简单去重：同名分组不允许重复
                    if (id == null && appDb.bookGroupDao.all().any { it.groupName == name }) {
                        json(ex, 400, mapOf("ok" to false, "error" to "已存在同名分组"))
                        return
                    }
                    appDb.bookGroupDao.upsert(group)
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "groupId" to group.groupId,
                            "groupName" to group.groupName,
                        )
                    )
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "groups/delete" -> {
                val id = q["id"]?.toLongOrNull()
                if (id == null) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少分组 id"))
                    return
                }
                appDb.bookGroupDao.delete(id)
                // 组内书籍回落到"未分组"，不跟着一起消失
                val moved = appDb.bookDao.all().filter { it.group == id }
                moved.forEach { it.group = 0L }
                if (moved.isNotEmpty()) appDb.bookDao.update(*moved.toTypedArray())
                json(ex, 200, mapOf("ok" to true, "movedBooks" to moved.size))
            }

            "book/group" -> {
                val bookUrl = q["bookUrl"] ?: ""
                val groupId = q["group"]?.toLongOrNull() ?: 0L
                val book = appDb.bookDao.getBook(bookUrl)
                if (book == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书不在书架中"))
                    return
                }
                book.group = groupId
                appDb.bookDao.update(book)
                json(ex, 200, mapOf("ok" to true, "group" to groupId))
            }

            // ------------------------------------------------ 本地书籍
            //
            // 分章逻辑直接复用移植过来的 TextFile（legado 原版实现，
            // 含 26 条打磨过的中文小说分章正则），不重写。
            "local/import" -> {
                val path = q["path"] ?: ""
                if (path.isBlank()) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少文件路径"))
                    return
                }
                val file = File(path)
                if (!file.exists() || !file.isFile) {
                    json(ex, 404, mapOf("ok" to false, "error" to "文件不存在: $path"))
                    return
                }
                try {
                    val book = LocalBook.createBook(file)
                    val isEpub = file.extension.equals("epub", true)
                    // EPUB：先读元数据（书名/作者/简介/封面），再取目录
                    if (isEpub) runCatching { EpubFile.upBookInfo(book) }
                        .onFailure { LogUtils.w("AppServer", "读取 EPUB 元数据失败: ${it.message}") }
                    if (book.name.isBlank()) book.name = file.nameWithoutExtension

                    val chapters = if (isEpub) EpubFile.getChapterList(book)
                    else TextFile.getChapterList(book)
                    if (chapters.isEmpty()) {
                        json(ex, 200, mapOf("ok" to false, "error" to "没能识别出任何章节，可能是文件损坏或格式不支持"))
                        return
                    }
                    chapters.forEachIndexed { i, c -> c.index = i }
                    book.totalChapterNum = chapters.size
                    book.latestChapterTime = file.lastModified()
                    appDb.bookDao.insert(book)
                    appDb.bookChapterDao.delete(book.bookUrl)
                    appDb.bookChapterDao.insert(*chapters.toTypedArray())
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "format" to if (isEpub) "epub" else "txt",
                            "book" to bookBrief(book),
                            "total" to chapters.size,
                            "author" to book.author,
                            "intro" to (book.intro ?: ""),
                            "coverUrl" to (book.coverUrl ?: ""),
                            "firstChapter" to (chapters.firstOrNull()?.title ?: ""),
                            "charset" to book.charset,
                        )
                    )
                } catch (e: Exception) {
                    LogUtils.e("AppServer", "导入本地书失败: ${e.message}", e)
                    json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "导入失败")))
                }
            }

            "local/scan" -> {
                // 扫描一个目录下的 txt/epub，便于用户批量挑书
                val dir = q["dir"] ?: ""
                val d = File(dir)
                if (!d.isDirectory) {
                    json(ex, 404, mapOf("ok" to false, "error" to "目录不存在: $dir"))
                    return
                }
                val files = d.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in setOf("txt", "epub", "umd") }
                    ?.sortedBy { it.name }
                    ?.map {
                        mapOf(
                            "name" to it.nameWithoutExtension,
                            "path" to it.absolutePath,
                            "size" to it.length(),
                            "sizeText" to ConvertUtils.formatFileSize(it.length()),
                        )
                    } ?: emptyList()
                json(ex, 200, mapOf("ok" to true, "dir" to d.absolutePath, "files" to files))
            }

            "local/toc" -> {
                val bookUrl = q["bookUrl"] ?: ""
                val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
                if (chapters.isEmpty()) {
                    json(ex, 200, mapOf("ok" to false, "error" to "本地书目录为空，请重新导入"))
                    return
                }
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "total" to chapters.size,
                        "chapters" to chapters.map { ch ->
                            mapOf("index" to ch.index, "title" to ch.title, "url" to ch.url)
                        },
                    )
                )
            }

            "local/content" -> {
                val bookUrl = q["bookUrl"] ?: ""
                val book = appDb.bookDao.getBook(bookUrl)
                if (book == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书不在书架中"))
                    return
                }
                val index = q["index"]?.toIntOrNull() ?: 0
                val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
                val chapter = chapters.getOrNull(index)
                if (chapter == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "章节不存在"))
                    return
                }
                try {
                    // 正文读取同样走移植过来的 TextFile / EpubFile
                    // （TextFile 按 buffer 窗口读，不会整本载入内存）
                    val content = if (book.isEpub) EpubFile.getContent(book, chapter)
                    else TextFile.getContent(book, chapter)
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "index" to index,
                            "total" to chapters.size,
                            "title" to chapter.title,
                            "content" to content,
                            "prevIndex" to if (index > 0) index - 1 else -1,
                            "nextIndex" to if (index < chapters.size - 1) index + 1 else -1,
                        )
                    )
                } catch (e: Exception) {
                    LogUtils.e("AppServer", "读取本地正文失败: ${e.message}", e)
                    json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "读取失败")))
                }
            }

            // ------------------------------------------------ 订阅源（RSS）
            "rss/sources" -> when (ex.requestMethod) {
                "GET" -> {
                    val list = appDb.rssSourceDao.all().sortedByDescending { it.enabled }
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "total" to list.size,
                            "sources" to list.map {
                                mapOf(
                                    "url" to it.sourceUrl,
                                    "name" to it.sourceName,
                                    "group" to it.sourceGroup,
                                    "enabled" to it.enabled,
                                    "hasSort" to !it.sortUrl.isNullOrBlank(),
                                    "singleUrl" to it.singleUrl,
                                )
                            },
                        )
                    )
                }

                "POST" -> {
                    val body = readBody(ex)
                    val imported = importRssSources(body)
                    json(ex, 200, mapOf("ok" to true, "imported" to imported))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "rss/sorts" -> {
                val src = appDb.rssSourceDao.getRssSource(q["sourceUrl"] ?: "")
                if (src == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "订阅源不存在"))
                    return
                }
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "sourceName" to src.sourceName,
                        "kinds" to parseExploreKinds(src.sortUrl),
                    )
                )
            }

            "rss/articles" -> {
                val src = appDb.rssSourceDao.getRssSource(q["sourceUrl"] ?: "")
                if (src == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "订阅源不存在"))
                    return
                }
                val sortName = q["sortName"] ?: ""
                val sortUrl = q["sortUrl"] ?: src.sourceUrl
                val page = q["page"]?.toIntOrNull() ?: 1
                try {
                    val (articles, nextPage) = runBlocking {
                        withTimeout(searchTimeoutMs) {
                            Rss.getArticlesAwait(sortName, sortUrl, src, page, q["key"])
                        }
                    }
                    if (articles.isNotEmpty()) {
                        appDb.rssArticleDao.insert(*articles.toTypedArray())
                    }
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "page" to page,
                            "nextPage" to nextPage,
                            "articles" to articles.map { rssBrief(it) },
                        )
                    )
                } catch (e: Exception) {
                    val reason = if (e is kotlinx.coroutines.TimeoutCancellationException)
                        "超时（${searchTimeoutMs / 1000}s）" else (e.message ?: e.javaClass.simpleName)
                    json(ex, 200, mapOf("ok" to false, "error" to reason))
                }
            }

            "rss/content" -> {
                val src = appDb.rssSourceDao.getRssSource(q["sourceUrl"] ?: "")
                if (src == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "订阅源不存在"))
                    return
                }
                val article = appDb.rssArticleDao
                    .get(src.sourceUrl, q["link"] ?: "", q["sort"] ?: "")
                if (article == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "文章不存在"))
                    return
                }
                try {
                    val content = runBlocking {
                        withTimeout(searchTimeoutMs) {
                            Rss.getContentAwait(article, src.ruleContent ?: "", src)
                        }
                    }
                    article.content = content
                    appDb.rssArticleDao.update(article)
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "title" to article.title,
                            "pubDate" to article.pubDate,
                            "content" to content,
                        )
                    )
                } catch (e: Exception) {
                    json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "读取失败")))
                }
            }

            "rss/read" -> {
                val src = appDb.rssSourceDao.getRssSource(q["sourceUrl"] ?: "")
                val link = q["link"] ?: ""
                val sort = q["sort"] ?: ""
                if (src != null && link.isNotBlank()) {
                    appDb.rssArticleDao.get(src.sourceUrl, link, sort)?.let {
                        it.read = true
                        appDb.rssArticleDao.update(it)
                    }
                    val key = "${src.sourceUrl}\u0001$link\u0001$sort"
                    if (!appDb.rssReadRecordDao.has(key)) {
                        appDb.rssReadRecordDao.insert(
                            RssReadRecord(record = key, origin = src.sourceUrl, sort = sort)
                        )
                    }
                }
                json(ex, 200, mapOf("ok" to true))
            }            /**
             * 流式搜索（SSE）——与手机端一致的体验：**哪个源先返回就先显示哪个源的结果**。
             *
             * 为什么需要它：导入真实书源后，总有几个源因为被墙、DNS 解析卡住等原因
             * 长时间不返回，而这些阻塞在 JVM 上不可中断（withTimeout 取消不了）。
             * 等全部完成再响应，用户只能看到"搜索中"然后超时；
             * 改成每完成一个源就推一批，用户立刻就能开始看书。
             */
            "search/stream" -> {
                val key = q["key"].orEmpty().trim()
                if (key.isEmpty()) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少搜索关键词"))
                    return
                }
                val onlyUrl = q["source"]
                val onlyGroup = q["group"]
                // 整包导入后可能有上千个源。全搜一遍既不现实也没意义——
                // 45s 预算只能跑到其中一小部分，用户拿到的其实是"随机的一部分源"的结果。
                // 因此设上限，并在 done 里如实告知被跳过了多少。
                // 用户显式选了分组/单个源时不设这个限制（他是有意缩范围的）。
                // 另外只挑"有搜索规则"的源——没配 searchUrl 的源搜了也必然空手，
                // 让它占掉一个并发位不值得。
                val allEnabled = appDb.bookSourceDao.enabled()
                    .filter { onlyUrl == null || it.bookSourceUrl == onlyUrl }
                    .filter { onlyGroup == null || (it.bookSourceGroup ?: "默认") == onlyGroup }
                    .filter { onlyUrl != null || !it.searchUrl.isNullOrBlank() }
                val cap = q["limit"]?.toIntOrNull()?.coerceIn(1, 2000)
                    ?: if (onlyUrl != null || onlyGroup != null) 2000 else maxSearchSources
                val sources = allEnabled.take(cap)
                val skipped = allEnabled.size - sources.size

                ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
                ex.responseHeaders.add("Cache-Control", "no-cache")
                ex.responseHeaders.add("Connection", "keep-alive")
                ex.sendResponseHeaders(200, 0)   // 0 = chunked

                val out = ex.responseBody
                var sentSources = 0
                var sentBooks = 0
                val started = System.currentTimeMillis()

                fun frame(event: String, data: Any) {
                    val payload = "event: $event\ndata: ${gson.toJson(data)}\n\n"
                    out.write(payload.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }

                try {
                    frame("start", mapOf("key" to key, "sourceCount" to sources.size, "skipped" to skipped))
                    if (sources.isEmpty()) {
                        frame("done", mapOf("ok" to true, "total" to 0, "message" to "没有启用的书源"))
                        return
                    }

                    val channel = kotlinx.coroutines.channels.Channel<
                        Triple<BookSource, List<SearchBook>, String?>>(
                        kotlinx.coroutines.channels.Channel.UNLIMITED
                    )
                    // 关键：搜索任务跑在**独立作用域**里，而不是 runBlocking 的子协程。
                    // 因为 runBlocking 会等待所有子协程结束，而卡在 DNS 解析上的协程
                    // 是不可取消的——那样循环结束后依然发不出 done 事件。
                    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                    sources.forEach { src ->
                        scope.launch {
                            val one = System.currentTimeMillis()
                            val outcome = try {
                                val list = withTimeout(searchTimeoutMs) {
                                    WebBook.searchBookAwait(src, key)
                                }
                                Triple(src, list, null as String?)
                            } catch (e: Exception) {
                                val reason =
                                    if (e is kotlinx.coroutines.TimeoutCancellationException)
                                        "超时（${searchTimeoutMs / 1000}s）"
                                    else e.message ?: e.javaClass.simpleName
                                Triple(src, emptyList<SearchBook>(), reason)
                            }
                            LogUtils.d(
                                "AppServer",
                                "书源《${src.bookSourceName}》用时 ${System.currentTimeMillis() - one}ms"
                            )
                            runCatching { channel.send(outcome) }
                        }
                    }

                    // 完成一个推一个；整体仍设预算，避免连接永久挂着
                    runBlocking {
                        val deadline = System.currentTimeMillis() + searchBudgetMs
                        var received = 0
                        while (received < sources.size && System.currentTimeMillis() < deadline) {
                            val remaining = deadline - System.currentTimeMillis()
                            val item = withTimeoutOrNull(remaining) { channel.receive() } ?: break
                            received++
                            val (src, books, err) = item
                            sentSources++
                            sentBooks += books.size
                            frame(
                                "source",
                                mapOf(
                                    "sourceName" to src.bookSourceName,
                                    "sourceUrl" to src.bookSourceUrl,
                                    "ok" to (err == null),
                                    "error" to err,
                                    "count" to books.size,
                                    "books" to books.map { searchBrief(it) },
                                )
                            )
                        }
                        if (received < sources.size) {
                            LogUtils.w(
                                "AppServer",
                                "流式搜索预算耗尽，${sources.size - received} 个书源未返回"
                            )
                        }
                    }
                    // 尽力取消；不等待（等待会再次被不可取消的阻塞拖住）
                    scope.cancel()
                    frame(
                        "done",
                        mapOf(
                            "ok" to true,
                            "sourceCount" to sources.size,
                            "repliedSources" to sentSources,
                            "total" to sentBooks,
                            "skipped" to skipped,
                            "elapsedMs" to (System.currentTimeMillis() - started),
                        )
                    )
                } catch (e: Exception) {
                    LogUtils.w("AppServer", "流式搜索中断: ${e.message}")
                    runCatching {
                        frame("error", mapOf("ok" to false, "error" to (e.message ?: "搜索中断")))
                    }
                } finally {
                    runCatching { out.close() }
                }
            }

            /**
             * 换源：为同一本书在其它书源里找精确匹配（书名+作者完全一致）。
             *
             * 用引擎的 `preciseSearchAwait`——就是 legado 换源用的那个接口，
             * 内部带 name/author 过滤器，找到一条即停。
             * 同样走 SSE：哪个源先找到就先显示，不必等全部。
             */
            "change-source/stream" -> {
                val name = q["name"].orEmpty().trim()
                val author = q["author"].orEmpty().trim()
                val excludeUrl = q["exclude"]
                if (name.isEmpty()) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少书名"))
                    return
                }
                val sources = appDb.bookSourceDao.enabled()
                    .filter { it.bookSourceUrl != excludeUrl }

                ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
                ex.responseHeaders.add("Cache-Control", "no-cache")
                ex.sendResponseHeaders(200, 0)

                val out = ex.responseBody
                var found = 0
                val started = System.currentTimeMillis()

                fun frame(event: String, data: Any) {
                    out.write(
                        "event: $event\ndata: ${gson.toJson(data)}\n\n"
                            .toByteArray(StandardCharsets.UTF_8)
                    )
                    out.flush()
                }

                try {
                    frame(
                        "start",
                        mapOf("name" to name, "author" to author, "sourceCount" to sources.size)
                    )
                    val channel = kotlinx.coroutines.channels.Channel<
                        Triple<BookSource, Book?, String?>>(
                        kotlinx.coroutines.channels.Channel.UNLIMITED
                    )
                    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                    sources.forEach { src ->
                        scope.launch {
                            val outcome = try {
                                val r = withTimeout(searchTimeoutMs) {
                                    WebBook.preciseSearchAwait(src, name, author)
                                }
                                Triple(src, r.getOrNull(), r.exceptionOrNull()?.message)
                            } catch (e: Exception) {
                                Triple(src, null, e.message ?: e.javaClass.simpleName)
                            }
                            runCatching { channel.send(outcome) }
                        }
                    }

                    runBlocking {
                        val deadline = System.currentTimeMillis() + searchBudgetMs
                        var received = 0
                        while (received < sources.size && System.currentTimeMillis() < deadline) {
                            val item = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
                                channel.receive()
                            } ?: break
                            received++
                            val (src, book, err) = item
                            if (book != null) found++
                            frame(
                                "candidate",
                                mapOf(
                                    "sourceName" to src.bookSourceName,
                                    "sourceUrl" to src.bookSourceUrl,
                                    "found" to (book != null),
                                    "error" to err,
                                    "book" to book?.let { bookBrief(it) },
                                )
                            )
                        }
                    }
                    scope.cancel()
                    frame(
                        "done",
                        mapOf(
                            "ok" to true,
                            "found" to found,
                            "elapsedMs" to (System.currentTimeMillis() - started),
                        )
                    )
                } catch (e: Exception) {
                    LogUtils.w("AppServer", "换源搜索中断: ${e.message}")
                } finally {
                    runCatching { out.close() }
                }
            }

            /** 应用换源：把书的来源切到新书源，让客户端重新取目录 */
            "change-source/apply" -> {
                val bookUrl = q["bookUrl"] ?: ""
                val newSourceUrl = q["sourceUrl"] ?: ""
                val newBookUrl = q["newBookUrl"] ?: ""
                val book = appDb.bookDao.getBook(bookUrl)
                if (book == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书不在书架中"))
                    return
                }
                val src = appDb.bookSourceDao.getBookSource(newSourceUrl)
                if (src == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "目标书源不存在"))
                    return
                }
                // 保留阅读进度与书名，只换来源与书址
                val oldUrl = book.bookUrl
                book.origin = src.bookSourceUrl
                book.originName = src.bookSourceName
                book.bookUrl = newBookUrl.ifBlank { oldUrl }
                book.tocUrl = book.bookUrl
                book.latestChapterTime = 0L   // 强制重新取目录
                appDb.bookDao.update(book)
                appDb.bookChapterDao.delete(oldUrl)
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "book" to bookBrief(book),
                        "message" to "已切换到《${src.bookSourceName}》",
                    )
                )
            }

            /**
             * 书源调试：把引擎里 142 个 `Debug.log` 调用点的输出实时推给界面。
             *
             * 这些日志正是 legado 调试界面显示的内容——规则在哪一步拿了什么、
             * 哪条规则匹配为空、请求耗时多少。排查失效书源时最有用。
             *
             * 实现方式是挂上 `Debug.callback`（引擎自带的钩子），
             * 因为它是个全局单例，同一时刻只允许一个调试会话。
             */
            "debug/stream" -> {
                val sourceUrl = q["sourceUrl"] ?: ""
                val key = q["key"].orEmpty().ifBlank { "测试" }
                val src = appDb.bookSourceDao.getBookSource(sourceUrl)
                if (src == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书源不存在"))
                    return
                }
                if (!debugLock.compareAndSet(false, true)) {
                    json(ex, 409, mapOf("ok" to false, "error" to "已有调试会话在运行，请稍候"))
                    return
                }

                ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
                ex.responseHeaders.add("Cache-Control", "no-cache")
                ex.sendResponseHeaders(200, 0)

                val out = ex.responseBody
                val queue = java.util.concurrent.LinkedBlockingQueue<String>(4096)
                val previous = Debug.callback
                var emitted = 0
                val started = System.currentTimeMillis()

                fun frame(event: String, data: Any) {
                    runCatching {
                        out.write(
                            "event: $event\ndata: ${gson.toJson(data)}\n\n"
                                .toByteArray(StandardCharsets.UTF_8)
                        )
                        out.flush()
                    }
                }

                try {
                    Debug.callback = object : Debug.Callback {
                        override fun onDebugMessage(sourceUrl: String?, msg: String?) {
                            val m = msg ?: return
                            // 只看当前书源的消息，避免多源并发时混淆
                            if (sourceUrl != null && sourceUrl != src.bookSourceUrl) return
                            queue.offer(m)
                        }
                    }
                    Debug.startChecking(src)

                    frame(
                        "start",
                        mapOf(
                            "sourceName" to src.bookSourceName,
                            "key" to key,
                            "searchUrl" to (src.searchUrl ?: ""),
                        )
                    )

                    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                    val done = java.util.concurrent.atomic.AtomicBoolean(false)
                    var searchResult: String? = null
                    var bookCount = 0
                    scope.launch {
                        searchResult = try {
                            bookCount = withTimeout(searchTimeoutMs) {
                                WebBook.searchBookAwait(src, key)
                            }.size
                            null
                        } catch (e: Exception) {
                            e.message ?: e.javaClass.simpleName
                        }
                        done.set(true)
                    }

                    // 边跑边推日志
                    val deadline = System.currentTimeMillis() + searchTimeoutMs + 5_000
                    while (System.currentTimeMillis() < deadline) {
                        val msg = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (msg != null) {
                            emitted++
                            frame("log", mapOf("msg" to msg))
                        } else if (done.get() && queue.isEmpty()) {
                            break
                        }
                    }
                    // 收尾时把剩下的也推完
                    while (true) {
                        val msg = queue.poll() ?: break
                        emitted++
                        frame("log", mapOf("msg" to msg))
                    }
                    scope.cancel()

                    frame(
                        "done",
                        mapOf(
                            "ok" to (searchResult == null),
                            "bookCount" to bookCount,
                            "logCount" to emitted,
                            "error" to searchResult,
                            "elapsedMs" to (System.currentTimeMillis() - started),
                        )
                    )
                } catch (e: Exception) {
                    LogUtils.w("AppServer", "调试中断: ${e.message}")
                } finally {
                    Debug.finishChecking()
                    Debug.callback = previous
                    debugLock.set(false)
                    runCatching { out.close() }
                }
            }

            /**
             * 备份：把用户数据整体导出成一个 JSON。
             *
             * 只备份"用户资产"——书源、书架、目录、分组、净化规则、书签、
             * 阅读记录、订阅源与文章、本地 TXT 分章规则。
             * 缓存（caches / cookies / search_books）属临时数据，不备份。
             */
            "backup" -> {
                val data = linkedMapOf<String, Any>(
                    "version" to 1,
                    "time" to System.currentTimeMillis(),
                    "app" to "legado-win",
                    "book_sources" to appDb.bookSourceDao.all(),
                    "books" to appDb.bookDao.all(),
                    "chapters" to appDb.bookChapterDao.all(),
                    "book_groups" to appDb.bookGroupDao.all(),
                    "replace_rules" to appDb.replaceRuleDao.all(),
                    "bookmarks" to appDb.bookmarkDao.all(),
                    "read_record" to appDb.readRecordDao.all(),
                    "rss_sources" to appDb.rssSourceDao.all(),
                    "rss_articles" to appDb.rssArticleDao.all(),
                    "rss_read_record" to appDb.rssReadRecordDao.all(),
                    "rss_stars" to appDb.rssStarDao.all(),
                    "txt_toc_rules" to appDb.txtTocRuleDao.all(),
                )
                val bytes = gson.toJson(data).toByteArray(StandardCharsets.UTF_8)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.responseHeaders.add(
                    "Content-Disposition",
                    "attachment; filename=\"legado-backup.json\"; " +
                        "filename*=UTF-8''legado-backup-$stamp.json"
                )
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                LogUtils.i("AppServer", "已导出备份：${appDb.bookDao.all().size} 本书")
            }

            /**
             * 恢复：导入备份 JSON。
             *
             * 采取**合并**而不是清空后覆盖——备份恢复是低频高风险操作，
             * 合并能避免"恢复一个旧备份把新加的书冲掉"。
             * 返回各表实际写入条数，让用户看得见发生了什么。
             */
            "restore" -> {
                val body = readBody(ex)
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (root == null) {
                    json(ex, 400, mapOf("ok" to false, "error" to "不是合法的备份 JSON"))
                    return
                }
                if (root.get("book_sources") == null && root.get("books") == null) {
                    json(ex, 400, mapOf("ok" to false, "error" to "这不像 legado-win 的备份文件"))
                    return
                }

                val counts = linkedMapOf<String, Int>()
                try {
                    fun bookSources() {
                        val arr = root.getAsJsonArray("book_sources") ?: return
                        val type = com.google.gson.reflect.TypeToken.getParameterized(
                            List::class.java, io.legado.app.data.entities.BookSource::class.java
                        ).type
                        val list = gson.fromJson<List<io.legado.app.data.entities.BookSource>>(
                            arr.toString(), type
                        ) ?: return
                        appDb.bookSourceDao.insert(*list.toTypedArray())
                        counts["book_sources"] = list.size
                    }

                    fun books() {
                        val arr = root.getAsJsonArray("books") ?: return
                        val type = com.google.gson.reflect.TypeToken.getParameterized(
                            List::class.java, io.legado.app.data.entities.Book::class.java
                        ).type
                        val list = gson.fromJson<List<io.legado.app.data.entities.Book>>(
                            arr.toString(), type
                        ) ?: return
                        appDb.bookDao.insert(*list.toTypedArray())
                        counts["books"] = list.size
                    }

                    fun chapters() {
                        val arr = root.getAsJsonArray("chapters") ?: return
                        val type = com.google.gson.reflect.TypeToken.getParameterized(
                            List::class.java, io.legado.app.data.entities.BookChapter::class.java
                        ).type
                        val list = gson.fromJson<List<io.legado.app.data.entities.BookChapter>>(
                            arr.toString(), type
                        ) ?: return
                        appDb.bookChapterDao.insert(*list.toTypedArray())
                        counts["chapters"] = list.size
                    }

                    fun replaceRules() {
                        val arr = root.getAsJsonArray("replace_rules") ?: return
                        val type = com.google.gson.reflect.TypeToken.getParameterized(
                            List::class.java, io.legado.app.data.entities.ReplaceRule::class.java
                        ).type
                        val list = gson.fromJson<List<io.legado.app.data.entities.ReplaceRule>>(
                            arr.toString(), type
                        ) ?: return
                        appDb.replaceRuleDao.insert(*list.toTypedArray())
                        counts["replace_rules"] = list.size
                    }

                    fun bookmarks() {
                        val arr = root.getAsJsonArray("bookmarks") ?: return
                        val type = com.google.gson.reflect.TypeToken.getParameterized(
                            List::class.java, io.legado.app.data.entities.Bookmark::class.java
                        ).type
                        val list = gson.fromJson<List<io.legado.app.data.entities.Bookmark>>(
                            arr.toString(), type
                        ) ?: return
                        appDb.bookmarkDao.insert(*list.toTypedArray())
                        counts["bookmarks"] = list.size
                    }

                    fun rssSources() {
                        val arr = root.getAsJsonArray("rss_sources") ?: return
                        val type = com.google.gson.reflect.TypeToken.getParameterized(
                            List::class.java, io.legado.app.data.entities.RssSource::class.java
                        ).type
                        val list = gson.fromJson<List<io.legado.app.data.entities.RssSource>>(
                            arr.toString(), type
                        ) ?: return
                        appDb.rssSourceDao.insert(*list.toTypedArray())
                        counts["rss_sources"] = list.size
                    }

                    fun bookGroups() {
                        val arr = root.getAsJsonArray("book_groups") ?: return
                        val type = com.google.gson.reflect.TypeToken.getParameterized(
                            List::class.java, io.legado.app.data.entities.BookGroup::class.java
                        ).type
                        val list = gson.fromJson<List<io.legado.app.data.entities.BookGroup>>(
                            arr.toString(), type
                        ) ?: return
                        appDb.bookGroupDao.insert(*list.toTypedArray())
                        counts["book_groups"] = list.size
                    }

                    fun txtTocRules() {
                        val arr = root.getAsJsonArray("txt_toc_rules") ?: return
                        val type = com.google.gson.reflect.TypeToken.getParameterized(
                            List::class.java, io.legado.app.data.entities.TxtTocRule::class.java
                        ).type
                        val list = gson.fromJson<List<io.legado.app.data.entities.TxtTocRule>>(
                            arr.toString(), type
                        ) ?: return
                        appDb.txtTocRuleDao.insert(*list.toTypedArray())
                        counts["txt_toc_rules"] = list.size
                    }

                    bookSources(); books(); chapters(); replaceRules()
                    bookmarks(); rssSources(); bookGroups(); txtTocRules()
                } catch (e: Exception) {
                    LogUtils.e("AppServer", "恢复失败: ${e.message}", e)
                    json(
                        ex, 200,
                        mapOf("ok" to false, "error" to (e.message ?: "恢复失败"), "restored" to counts)
                    )
                    return
                }

                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "restored" to counts,
                        "total" to counts.values.sum(),
                        "message" to "已合并恢复 ${counts.values.sum()} 条数据（同名条目已覆盖）",
                    )
                )
            }

            /**
             * 漫画阅读：把章节正文里的 `<img>` 抽成图片地址列表。
             *
             * 规则引擎与抓取链路和文字书完全一致（同一个 `ruleContent`），
             * 区别只在于最后一步——文字书直接给文本，图片书按 `imgPattern` 抽图片地址
             * （复用上游 `BookHelp.flowImages` 的实现）。
             * 是否漫画书由书源的 `bookSourceType` 决定（2 = 图片）。
             */
            "manga/content" -> {
                val bookUrl = q["bookUrl"] ?: ""
                val book = appDb.bookDao.getBook(bookUrl)
                if (book == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "书不在书架中"))
                    return
                }
                val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
                val index = q["index"]?.toIntOrNull() ?: 0
                val chapter = chapters.getOrNull(index)
                if (chapter == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "章节不存在，请先获取目录"))
                    return
                }
                try {
                    // EpubFile.getContent 返回可空，统一成非空字符串
                    val content: String = (if (book.isLocal) {
                        if (book.isEpub) EpubFile.getContent(book, chapter)
                        else TextFile.getContent(book, chapter)
                    } else {
                        val src = appDb.bookSourceDao.getBookSource(book.origin)
                            ?: throw IllegalStateException("书源不存在：${book.origin}")
                        runBlocking {
                            withTimeout(searchTimeoutMs) {
                                WebBook.getContentAwait(
                                    src, book, chapter, chapters.getOrNull(index + 1)?.url, false
                                )
                            }
                        }
                    }).orEmpty()
                    // 图片地址同样做兜底解析（章节 url 可能是相对的）
                    val srcBase = appDb.bookSourceDao.getBookSource(book.origin)?.bookSourceUrl
                    val images = BookHelp.flowImages(chapter, content)
                        .map { absolutize(it, chapter.url, srcBase) ?: it }
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "index" to index,
                            "total" to chapters.size,
                            "title" to chapter.title,
                            "images" to images,
                            "count" to images.size,
                            "prevIndex" to if (index > 0) index - 1 else -1,
                            "nextIndex" to if (index < chapters.size - 1) index + 1 else -1,
                        )
                    )
                } catch (e: Exception) {
                    LogUtils.e("AppServer", "读取漫画章节失败: ${e.message}", e)
                    json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "读取失败")))
                }
            }

            /** 判断书架上的书哪些是漫画书（按书源类型）——与 book/kind 同一实现 */
            "manga/check" -> serveBookKind(ex, q)
            /**
             * 主题编辑器：读写自定义配色。
             *
             * 颜色用 ARGB 整数（与上游 `ThemeConfig.Config` 一致），
             * 前端把它转成 CSS 变量应用；夜间配色单独一套。
             */
            "theme" -> when (ex.requestMethod) {
                "GET" -> {
                    val c = ThemeConfig.getDurConfig()
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "theme" to c.toMap(),
                            "defaults" to ThemeConfig.Config().toMap(),
                        )
                    )
                }

                "POST" -> {
                    // 恢复默认不需要请求体，先处理它再去解析 JSON
                    if (q["reset"] == "1") {
                        ThemeConfig.reset()
                        json(ex, 200, mapOf("ok" to true, "message" to "已恢复默认配色"))
                        return
                    }
                    val obj = runCatching { JsonParser.parseString(readBody(ex)).asJsonObject }
                        .getOrNull()
                    if (obj == null) {
                        json(ex, 400, mapOf("ok" to false, "error" to "请求体必须是 JSON 对象"))
                        return
                    }
                    val c = ThemeConfig.getDurConfig()
                    // 通用地接受所有配色字段，日间/夜间成对
                    listOf(
                        "primary", "accent", "background", "backgroundNight",
                        "textColor", "textColorNight", "mutedColor", "mutedColorNight",
                        "panelColor", "panelColorNight", "borderColor", "borderColorNight",
                    ).forEach { key ->
                        val v = obj.get(key) ?: return@forEach
                        if (v.isJsonNull) return@forEach
                        val n = v.asInt
                        when (key) {
                            "primary" -> c.primary = n
                            "accent" -> c.accent = n
                            "background" -> c.background = n
                            "backgroundNight" -> c.backgroundNight = n
                            "textColor" -> c.textColor = n
                            "textColorNight" -> c.textColorNight = n
                            "mutedColor" -> c.mutedColor = n
                            "mutedColorNight" -> c.mutedColorNight = n
                            "panelColor" -> c.panelColor = n
                            "panelColorNight" -> c.panelColorNight = n
                            "borderColor" -> c.borderColor = n
                            "borderColorNight" -> c.borderColorNight = n
                        }
                    }
                    obj.get("name")?.let { if (!it.isJsonNull) c.name = it.asString }
                    obj.get("nameNight")?.let { if (!it.isJsonNull) c.nameNight = it.asString }
                    obj.get("isNightTheme")?.let { if (!it.isJsonNull) c.isNightTheme = it.asBoolean }
                    ThemeConfig.upConfig(c)
                    LogUtils.i("AppServer", "主题已更新")
                    json(ex, 200, mapOf("ok" to true, "theme" to c.toMap()))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            /**
             * 视频 / 音频阅读：书源类型 4（视频）与 1（音频）。
             *
             * 与漫画同理——引擎侧完全一致，区别只在取到内容之后怎么用：
             * 漫画抽图片地址，音视频把内容当播放地址
             * （legado 的 `VideoPlay` / `AudioPlay` 也是这样）。
             * 两者共用 `serveMediaChapter`，只有前端播放器元素不同。
             */
            "video/content" -> serveMediaChapter(ex, q, "video")
            "audio/content" -> serveMediaChapter(ex, q, "audio")

            /** 一次问清这本书是什么类型，前端据此选阅读器（文字/漫画/音频/视频） */
            "book/kind" -> serveBookKind(ex, q)
            "video/check" -> serveBookKind(ex, q)
            "audio/check" -> serveBookKind(ex, q)

            // ------------------------------------------------ 字典查询
            //
            // legado 的词典：规则与书源同构——urlRule 请求接口（{{key}} 为关键词），
            // showRule 从返回内容里提取要展示的文本。这里直接复用引擎的
            // AnalyzeUrl + AnalyzeRule，不另写解析逻辑。
            "dict" -> when (ex.requestMethod) {
                "GET" -> json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "total" to appDb.dictRuleDao.all().size,
                        "rules" to appDb.dictRuleDao.all().sortedBy { it.sortNumber }.map { dictBrief(it) },
                    )
                )

                "POST" -> {
                    val rule = runCatching {
                        gson.fromJson(readBody(ex), DictRule::class.java)
                    }.getOrNull()
                    if (rule == null || rule.name.isBlank() || rule.urlRule.isBlank()) {
                        json(ex, 400, mapOf("ok" to false, "error" to "词典名称与查询地址不能为空"))
                        return
                    }
                    appDb.dictRuleDao.upsert(rule)
                    json(ex, 200, mapOf("ok" to true, "rule" to dictBrief(rule)))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "dict/delete" -> {
                val name = q["name"] ?: ""
                appDb.dictRuleDao.delete(name)
                json(ex, 200, mapOf("ok" to true))
            }

            /**
             * 查询词典：并发查所有启用的词典，逐条返回结果。
             * 单个词典失败不影响其它词典——这是查询类功能的常识。
             */
            "dict/query" -> {
                val word = q["word"].orEmpty().trim()
                if (word.isEmpty()) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少查询词"))
                    return
                }
                val only = q["name"]
                val rules = appDb.dictRuleDao.enabled().filter { only == null || it.name == only }
                if (rules.isEmpty()) {
                    json(ex, 200, mapOf("ok" to true, "word" to word, "results" to emptyList<Any>()))
                    return
                }
                val results = runBlocking {
                    withContext(Dispatchers.IO) {
                        rules.map { rule ->
                            async {
                                val started = System.currentTimeMillis()
                                try {
                                    val analyzeUrl = AnalyzeUrl(rule.urlRule, key = word)
                                    val body = analyzeUrl.getStrResponseAwait().body.orEmpty()
                                    val text = AnalyzeRule()
                                        .setContent(body)
                                        .setBaseUrl(analyzeUrl.url)
                                        .getString(rule.showRule)
                                    mapOf(
                                        "name" to rule.name,
                                        "ok" to true,
                                        "content" to text,
                                        "elapsedMs" to (System.currentTimeMillis() - started),
                                    )
                                } catch (e: Exception) {
                                    mapOf(
                                        "name" to rule.name,
                                        "ok" to false,
                                        "error" to (e.message ?: e.javaClass.simpleName),
                                        "elapsedMs" to (System.currentTimeMillis() - started),
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                }
                json(ex, 200, mapOf("ok" to true, "word" to word, "results" to results))
            }

            // ------------------------------------------------ WebDAV 同步
            //
            // 直接复用已有的 backup / restore：把那份 JSON 上传/下载到 WebDAV 即可。
            // 只用到 WebDAV 最基本的三个动作：MKCOL 建目录、PUT 上传、GET 下载。
            "webdav" -> when (ex.requestMethod) {
                "GET" -> {
                    val list = appDb.serverDao.all().map { s ->
                        val cfg = runCatching {
                            JsonParser.parseString(s.config ?: "{}").asJsonObject
                        }.getOrNull()
                        mapOf(
                            "id" to s.id,
                            "name" to s.name,
                            "type" to s.type.name,
                            "url" to (cfg?.get("url")?.takeIf { !it.isJsonNull }?.asString ?: ""),
                            "username" to (cfg?.get("username")?.takeIf { !it.isJsonNull }?.asString ?: ""),
                            "hasPassword" to (cfg?.get("password")?.takeIf { !it.isJsonNull }?.asString?.isNotEmpty() == true),
                        )
                    }
                    json(ex, 200, mapOf("ok" to true, "total" to list.size, "servers" to list))
                }

                "POST" -> {
                    val obj = runCatching { JsonParser.parseString(readBody(ex)).asJsonObject }
                        .getOrNull()
                    if (obj == null) {
                        json(ex, 400, mapOf("ok" to false, "error" to "请求体必须是 JSON 对象"))
                        return
                    }
                    val url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    if (url.isBlank()) {
                        json(ex, 400, mapOf("ok" to false, "error" to "WebDAV 地址不能为空"))
                        return
                    }
                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
                        ?: System.currentTimeMillis()
                    val existing = appDb.serverDao.get(id)
                    val cfgObj = com.google.gson.JsonObject()
                    cfgObj.addProperty("url", url)
                    cfgObj.addProperty(
                        "username",
                        obj.get("username")?.takeIf { !it.isJsonNull }?.asString
                            ?: runCatching {
                                JsonParser.parseString(existing?.config ?: "{}").asJsonObject
                                    .get("username")?.asString
                            }.getOrNull().orEmpty()
                    )
                    // 密码留空表示"不修改"
                    val pwd = obj.get("password")?.takeIf { !it.isJsonNull }?.asString
                    val oldPwd = runCatching {
                        JsonParser.parseString(existing?.config ?: "{}").asJsonObject
                            .get("password")?.asString
                    }.getOrNull().orEmpty()
                    cfgObj.addProperty("password", if (pwd.isNullOrEmpty()) oldPwd else pwd)

                    val server = existing ?: Server(id = id)
                    server.name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "WebDAV"
                    server.config = gson.toJson(cfgObj)
                    appDb.serverDao.upsert(server)
                    json(ex, 200, mapOf("ok" to true, "id" to id, "message" to "已保存"))
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "webdav/delete" -> {
                q["id"]?.toLongOrNull()?.let { appDb.serverDao.delete(it) }
                json(ex, 200, mapOf("ok" to true))
            }

            /** 备份到 WebDAV：上传当前用户数据的 JSON */
            "webdav/backup" -> {
                val server = resolveWebDav(q["id"])
                if (server == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "WebDAV 配置不存在"))
                    return
                }
                try {
                    val (url, user, pwd) = server
                    val fileName = q["file"] ?: "legado-backup.json"
                    val target = url.trimEnd('/') + "/" + fileName
                    val body = buildBackupJson()
                    ensureWebDavDir(url, user, pwd)
                    val code = webDavPut(target, user, pwd, body)
                    json(
                        ex, 200, mapOf(
                            "ok" to (code in 200..299),
                            "code" to code,
                            "file" to fileName,
                            "bytes" to body.size,
                            "message" to if (code in 200..299) "已备份 $fileName（${body.size} 字节）"
                            else "上传失败，HTTP $code",
                        )
                    )
                } catch (e: Exception) {
                    json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "备份失败")))
                }
            }

            /** 从 WebDAV 恢复 */
            "webdav/restore" -> {
                val server = resolveWebDav(q["id"])
                if (server == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "WebDAV 配置不存在"))
                    return
                }
                try {
                    val (url, user, pwd) = server
                    val fileName = q["file"] ?: "legado-backup.json"
                    val target = url.trimEnd('/') + "/" + fileName
                    val text = webDavGet(target, user, pwd)
                        ?: throw IllegalStateException("WebDAV 上没有找到 $fileName")
                    val restored = applyBackup(text)
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "file" to fileName,
                            "restored" to restored,
                            "total" to restored.values.sum(),
                            "message" to "已从 WebDAV 恢复 ${restored.values.sum()} 条数据",
                        )
                    )
                } catch (e: Exception) {
                    json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "恢复失败")))
                }
            }            // ------------------------------------------------ 主题（命名主题）
            //
            // 与 /api/theme 的分工：
            //   /api/theme   = 当前生效的那一套配色（引擎 ThemeConfig 的读写）
            //   /api/themes  = 可保存/切换/删除的命名主题集合（预设 + 用户自定义）
            "themes" -> when (ex.requestMethod) {
                "GET" -> {
                    // 预设与自定义合并；同名时以用户保存的为准
                    val custom = ThemeStore.all().associateBy { it.name }
                    val presets = ThemeDef.presets()
                    val merged = (presets.filter { it.name !in custom } + ThemeStore.all())
                        .sortedWith(compareBy({ it.isNight }, { it.name }))
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "total" to merged.size,
                            "themes" to merged.map { it.toMap() + mapOf("custom" to (it.name in custom)) },
                            "activeDay" to ThemeStore.activeName(false),
                            "activeNight" to ThemeStore.activeName(true),
                            "isNightTheme" to ThemeConfig.isNightTheme,
                        )
                    )
                }

                "POST" -> {
                    val theme = runCatching {
                        gson.fromJson(readBody(ex), ThemeDef::class.java)
                    }.getOrNull()
                    if (theme == null || theme.name.isBlank()) {
                        json(ex, 400, mapOf("ok" to false, "error" to "主题名称不能为空"))
                        return
                    }
                    ThemeStore.save(theme)
                    val applied = q["apply"] == "1"
                    if (applied) ThemeStore.apply(theme)
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "theme" to theme.toMap(),
                            "applied" to applied,
                            "message" to if (applied) "已保存并应用《${theme.name}》" else "已保存《${theme.name}》",
                        )
                    )
                }

                else -> json(ex, 405, mapOf("ok" to false, "error" to "method not allowed"))
            }

            "themes/apply" -> {
                val name = q["name"] ?: ""
                val theme = ThemeStore.get(name) ?: ThemeDef.presets().firstOrNull { it.name == name }
                if (theme == null) {
                    json(ex, 404, mapOf("ok" to false, "error" to "主题不存在：$name"))
                    return
                }
                ThemeStore.apply(theme)
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "theme" to theme.toMap(),
                        "message" to "已应用《${theme.name}》",
                    )
                )
            }

            "themes/delete" -> {
                val name = q["name"] ?: ""
                if (ThemeDef.presets().any { it.name == name }) {
                    json(ex, 200, mapOf("ok" to false, "error" to "预设主题不能删除"))
                    return
                }
                ThemeStore.delete(name)
                json(ex, 200, mapOf("ok" to true, "message" to "已删除《$name》"))
            }

            /** 把"当前配色"另存为一套命名主题（在现有主题上微调后保存用） */
            "themes/snapshot" -> {
                val name = q["name"].orEmpty().trim()
                if (name.isEmpty()) {
                    json(ex, 400, mapOf("ok" to false, "error" to "请给主题起个名字"))
                    return
                }
                val isNight = q["isNight"]?.toBooleanStrictOrNull() ?: ThemeConfig.isNightTheme
                val theme = ThemeStore.snapshotCurrent(name, isNight)
                ThemeStore.save(theme)
                ThemeStore.setActiveName(isNight, name)
                json(ex, 200, mapOf("ok" to true, "theme" to theme.toMap(), "message" to "已保存《$name》"))
            }

            // ------------------------------------------------ 书源仓库（Yiove）
            //
            // 直接从书源仓库搜索并导入书源。接口是 Yiove 站点（Vue SPA）后端调的，
            // 站点本身是单页应用没法当订阅源用，但它的 API 可以直接用。
            //
            // 注意：该 API 会校验来源，必须带 Referer/Origin，否则只返回站点首页。
            "store/search" -> {
                val key = q["key"].orEmpty().trim()
                val type = q["type"]?.takeIf { it.isNotBlank() } ?: "book-sources"
                val page = q["page"]?.toIntOrNull() ?: 1
                if (type !in listOf("book-sources", "book-source-collections")) {
                    json(ex, 400, mapOf("ok" to false, "error" to "类型只能是 book-sources 或 book-source-collections"))
                    return
                }
                try {
                    // 搜索接口要求 search_key 非空（空串会返回 422）；
                    // 因此没给关键词时改走"列出书源/合集"接口，语义上也更合理。
                    val base = "https://shuyuan-api.yiove.com"
                    val url = if (key.isEmpty()) {
                        val p = if (type == "book-source-collections")
                            "book-source-collections" else "book-sources"
                        "$base/shuyuan/$p?page=$page&page_size=20"
                    } else {
                        "$base/shuyuan/search?search_key=" +
                            java.net.URLEncoder.encode(key, "UTF-8") +
                            "&search_type=$type&page=$page&page_size=20"
                    }
                    val text = fetchYiove(url)
                    val root = JsonParser.parseString(text).asJsonObject
                    val items = root.getAsJsonArray("items")?.map { el ->
                        val o = el.asJsonObject
                        mapOf(
                            "id" to (o.get("id")?.asString ?: ""),
                            "name" to (o.get("name")?.asString ?: ""),
                            "url" to (o.get("url")?.takeIf { !it.isJsonNull }?.asString ?: ""),
                            "description" to (o.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""),
                            "valid" to (o.get("is_valid")?.takeIf { !it.isJsonNull }?.asBoolean ?: true),
                            "viewTotal" to (o.get("view_total")?.takeIf { !it.isJsonNull }?.asInt ?: 0),
                        )
                    } ?: emptyList()
                    json(
                        ex, 200, mapOf(
                            "ok" to true,
                            "key" to key,
                            "type" to type,
                            "page" to (root.get("page")?.takeIf { !it.isJsonNull }?.asInt ?: page),
                            "total" to (root.get("total")?.takeIf { !it.isJsonNull }?.asInt
                                ?: items.size),
                            "items" to items,
                        )
                    )
                } catch (e: Exception) {
                    json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "搜索失败")))
                }
            }

            /** 导入仓库里的书源（单个或整个合集） */
            "store/import" -> {
                val id = q["id"].orEmpty().trim()
                val type = q["type"] ?: "book-source"
                if (id.isEmpty()) {
                    json(ex, 400, mapOf("ok" to false, "error" to "缺少 id"))
                    return
                }
                val path = when (type) {
                    "book-source-collection" -> "book-source-collection"
                    else -> "book-source"
                }
                try {
                    val text = fetchYiove("https://shuyuan-api.yiove.com/import/$path/$id")
                    val n = importSources(text)
                    json(
                        ex, 200, mapOf(
                            // 括号：Kotlin 里 infix 的 to 优先级高于 >
                            "ok" to (n > 0),
                            "imported" to n,
                            "message" to if (n > 0) "已导入 $n 个书源" else "这个条目里没有可导入的书源",
                        )
                    )
                } catch (e: Exception) {
                    json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "导入失败")))
                }
            }
            /**
             * 批量校验书源（SSE）。
             *
             * 用法与手机端的"校验书源"一致：拿一个关键词去每个源搜一次，
             * 能搜到书说明可用。导入整包书源后必然混着一批失效源，
             * 这个功能就是用来把它们挑出来的。
             *
             * 判定口径：
             *   - 搜到书            → 可用
             *   - 连接成功但 0 结果  → 可疑（可能只是关键词不匹配，也可能规则失效）
             *   - 报错/超时         → 失效
             */
            "sources/check/stream" -> {
                val key = q["key"].orEmpty().ifBlank { "测试" }
                val all = appDb.bookSourceDao.all()
                val sources = if (q["onlyEnabled"] == "0") all else all.filter { it.enabled }
                // 校验用较短超时：卡住的源要尽快跳过，否则整批太慢
                val perSourceMs = 15_000L

                ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
                ex.responseHeaders.add("Cache-Control", "no-cache")
                ex.sendResponseHeaders(200, 0)

                val out = ex.responseBody
                val started = System.currentTimeMillis()
                var okCount = 0
                var emptyCount = 0
                var badCount = 0

                fun frame(event: String, data: Any) {
                    runCatching {
                        out.write(
                            "event: $event\ndata: ${gson.toJson(data)}\n\n"
                                .toByteArray(StandardCharsets.UTF_8)
                        )
                        out.flush()
                    }
                }

                try {
                    frame("start", mapOf("key" to key, "total" to sources.size))
                    val channel = kotlinx.coroutines.channels.Channel<Map<String, Any?>>(
                        kotlinx.coroutines.channels.Channel.UNLIMITED
                    )
                    val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                    sources.forEach { src ->
                        scope.launch {
                            val one = System.currentTimeMillis()
                            val (books, err) = try {
                                val list = withTimeout(perSourceMs) {
                                    WebBook.searchBookAwait(src, key)
                                }
                                list to null as String?
                            } catch (e: Exception) {
                                val reason =
                                    if (e is kotlinx.coroutines.TimeoutCancellationException)
                                        "超时（${perSourceMs / 1000}s）"
                                    else e.message ?: e.javaClass.simpleName
                                emptyList<SearchBook>() to reason
                            }
                            runCatching {
                                channel.send(
                                    mapOf(
                                        "sourceUrl" to src.bookSourceUrl,
                                        "sourceName" to src.bookSourceName,
                                        "group" to src.bookSourceGroup,
                                        "status" to when {
                                            err != null -> "bad"
                                            books.isNotEmpty() -> "ok"
                                            else -> "empty"
                                        },
                                        "bookCount" to books.size,
                                        "elapsedMs" to (System.currentTimeMillis() - one),
                                        "error" to err,
                                        "firstBook" to books.firstOrNull()?.name,
                                    )
                                )
                            }
                        }
                    }

                    runBlocking {
                        // 整批预算：源多时也不能让用户无限等
                        val deadline = System.currentTimeMillis() +
                            (perSourceMs * 3).coerceAtMost(180_000L)
                        var received = 0
                        while (received < sources.size &&
                            System.currentTimeMillis() < deadline
                        ) {
                            val item = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
                                channel.receive()
                            } ?: break
                            received++
                            when (item["status"]) {
                                "ok" -> okCount++
                                "empty" -> emptyCount++
                                else -> badCount++
                            }
                            frame("result", item)
                        }
                    }
                    scope.cancel()
                    frame(
                        "done",
                        mapOf(
                            "ok" to true,
                            "total" to sources.size,
                            "okCount" to okCount,
                            "emptyCount" to emptyCount,
                            "badCount" to badCount,
                            "elapsedMs" to (System.currentTimeMillis() - started),
                        )
                    )
                } catch (e: Exception) {
                    LogUtils.w("AppServer", "书源校验中断: ${e.message}")
                } finally {
                    runCatching { out.close() }
                }
            }

            /** 批量停用失效书源（校验结果里 status=bad 的那批） */
            "sources/batch" -> {
                val urls = q["urls"].orEmpty().split(",").filter { it.isNotBlank() }
                val action = q["action"] ?: "disable"
                var n = 0
                urls.forEach { url ->
                    val src = appDb.bookSourceDao.getBookSource(url) ?: return@forEach
                    when (action) {
                        "disable" -> {
                            src.enabled = false
                            appDb.bookSourceDao.update(src)
                        }

                        "enable" -> {
                            src.enabled = true
                            appDb.bookSourceDao.update(src)
                        }

                        "delete" -> appDb.bookSourceDao.delete(src)
                    }
                    n++
                }
                LogUtils.i("AppServer", "批量$action 书源 $n 个")
                json(
                    ex, 200, mapOf(
                        "ok" to true,
                        "count" to n,
                        "message" to when (action) {
                            "disable" -> "已停用 $n 个书源"
                            "enable" -> "已启用 $n 个书源"
                            else -> "已删除 $n 个书源"
                        },
                    )
                )
            }

            "health" -> json(
                ex, 200, mapOf(
                    "ok" to true,
                    "engine" to "legado-win",
                    "sources" to appDb.bookSourceDao.all().size,
                    "books" to appDb.bookDao.all().size
                )
            )
            else -> json(ex, 404, mapOf("ok" to false, "error" to "未知接口: $api"))
        }
    }

    // ------------------------------------------------------------ 业务

    /** 单个书源的搜索超时。真实书源里常有被墙/挂掉的站，
     *  没有这个上限的话一个卡住的源会把整批搜索拖到 HTTP 超时。 */
    private val searchTimeoutMs = 25_000L

    /** 书源调试是全局单例（Debug.callback），同一时刻只允许一个会话 */
    private val debugLock = java.util.concurrent.atomic.AtomicBoolean(false)


    /**
     * 整批搜索的总预算。
     *
     * 为什么单源超时还不够：`withTimeout` 只能取消**可中断**的挂起点，
     * 而 DNS 解析（以及某些底层 socket 阻塞）在 JVM 上是不可中断的。
     * 实测导入 22 个真实书源后，搜索会因为个别源卡在解析上而无限等待，
     * 客户端最终只能看到 HTTP 超时。
     *
     * 因此这里再兜一层整体上限：到点就返回**已完成的那部分结果**，
     * 并取消剩余任务。宁可少几个源的结果，也不能让用户干等。
     */
    private val searchBudgetMs = 45_000L

    /**
     * 单次搜索最多使用多少个书源。
     *
     * 整包导入书源后可能有上千个（实测导入 970 条的合集后总数 1000），
     * 全搜一遍既不现实也没意义：45s 预算只够跑到其中一小部分，
     * 用户拿到的其实是"随机的一部分源"的结果，而且看不出来。
     * 因此设上限，并在响应里如实告知跳过了多少。
     */
    private val maxSearchSources = 150

    private data class SearchOutcome(
        val books: List<Map<String, Any?>>,
        val okSources: Int,
        val failed: List<Map<String, Any?>>,
        val timedOutSources: List<String> = emptyList(),
    )

    private fun searchAll(sources: List<BookSource>, key: String): SearchOutcome =
        runBlocking {
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                val jobs = sources.map { src ->
                    async {
                        try {
                            // 每个书源独立超时：慢的/挂掉的源不会拖累其它源
                            val list = withTimeout(searchTimeoutMs) {
                                WebBook.searchBookAwait(src, key)
                            }
                            Triple(src, list, null as String?)
                        } catch (e: Exception) {
                            val reason = when (e) {
                                is kotlinx.coroutines.TimeoutCancellationException ->
                                    "超时（${searchTimeoutMs / 1000}s）"

                                else -> e.message ?: e.javaClass.simpleName
                            }
                            LogUtils.w("AppServer", "书源《${src.bookSourceName}》搜索失败: $reason")
                            Triple(src, emptyList<SearchBook>(), reason)
                        }.also {
                            LogUtils.d(
                                "AppServer",
                                "书源《${src.bookSourceName}》用时 ${System.currentTimeMillis() - started}ms"
                            )
                        }
                    }
                }
                // 整体预算内全部完成就用全量结果；否则取已完成的部分
                withTimeoutOrNull(searchBudgetMs) { jobs.awaitAll() }
                    ?: jobs.mapNotNull {
                        if (it.isCompleted) runCatching { it.getCompleted() }.getOrNull() else null
                    }.also {
                        val stuck = jobs.count { !it.isCompleted }
                        if (stuck > 0) {
                            LogUtils.w("AppServer", "搜索预算耗尽，$stuck 个书源未返回，先返回已完成的部分")
                        }
                        jobs.forEach { it.cancel() }
                    }
            }
        }.let { list ->
            val books = list.flatMap { it.second }.map { sb ->
                mapOf(
                    "name" to sb.name,
                    "author" to sb.author,
                    "kind" to sb.kind,
                    "intro" to sb.intro,
                    "coverUrl" to sb.coverUrl,
                    "bookUrl" to sb.bookUrl,
                    "origin" to sb.origin,
                    "originName" to sb.originName,
                    "latestChapterTitle" to sb.latestChapterTitle,
                    "wordCount" to sb.wordCount,
                    "variableMap" to sb.variableMap,
                )
            }
            val failed = list.filter { it.third != null }
                .map { mapOf("name" to it.first.bookSourceName, "reason" to it.third) }
            SearchOutcome(
                books = books,
                okSources = list.count { it.third == null },
                failed = failed,
            )
        }

    // ------------------------------------------------------------ 发现规则解析
    /**
     * 解析书源的 exploreUrl。
     *
     * legado 有两种格式：
     *   1. 新版 JSON 数组：`[{"title":"月票榜","url":"/rank/yuepiao/page{{page}}/"}, ...]`
     *   2. 旧版按行文本：`月票榜::/rank/yuepiao/page{{page}}/`
     * 这里两种都支持，避免老书源用不了。
     */
    private fun parseExploreKinds(exploreUrl: String?): List<Map<String, Any?>> {
        if (exploreUrl.isNullOrBlank()) return emptyList()
        val text = exploreUrl.trim()

        // 新版 JSON
        if (text.startsWith("[")) {
            val arr = runCatching { JsonParser.parseString(text).asJsonArray }.getOrNull()
            if (arr != null) {
                return arr.mapNotNull { el ->
                    val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
                    val title = o.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val url = o.get("url")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (title.isBlank()) null else mapOf(
                        "title" to title,
                        "url" to url,
                        // url 为空表示这是个分组标题，不可点击
                        "group" to url.isBlank(),
                    )
                }
            }
        }

        // 旧版按行 `标题::地址`
        return text.lines().mapNotNull { line ->
            val l = line.trim()
            if (l.isBlank()) return@mapNotNull null
            val parts = l.split("::")
            val title = parts.getOrNull(0)?.trim() ?: return@mapNotNull null
            val url = parts.getOrNull(1)?.trim().orEmpty()
            if (title.isBlank()) null else mapOf(
                "title" to title,
                "url" to url,
                "group" to url.isBlank(),
            )
        }
    }

    private fun replaceBrief(r: ReplaceRule) = mapOf(
        "id" to r.id,
        "name" to r.name,
        "group" to r.group,
        "pattern" to r.pattern,
        "replacement" to r.replacement,
        "scope" to r.scope,
        "excludeScope" to r.excludeScope,
        "isEnabled" to r.isEnabled,
        "isRegex" to r.isRegex,
        "scopeTitle" to r.scopeTitle,
        "scopeContent" to r.scopeContent,
    )

    private fun bookmarkBrief(b: Bookmark) = mapOf(
        "time" to b.time,
        "bookName" to b.bookName,
        "bookAuthor" to b.bookAuthor,
        "chapterIndex" to b.chapterIndex,
        "chapterPos" to b.chapterPos,
        "chapterName" to b.chapterName,
        "bookText" to b.bookText,
        "content" to b.content,
    )

    /** 把毫秒时长变成「x 小时 y 分」这种可读文本 */
    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60000
        if (totalMinutes < 1) return "不足 1 分钟"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h} 小时 ${m} 分" else "${m} 分钟"
    }

    /** 搜索结果条目（普通搜索与流式搜索共用同一形状） */
    private fun searchBrief(sb: SearchBook) = mapOf(
        "name" to sb.name,
        "author" to sb.author,
        "kind" to sb.kind,
        "intro" to sb.intro,
        "coverUrl" to sb.coverUrl,
        "bookUrl" to sb.bookUrl,
        "origin" to sb.origin,
        "originName" to sb.originName,
        "latestChapterTitle" to sb.latestChapterTitle,
        "wordCount" to sb.wordCount,
        "variableMap" to sb.variableMap,
    )    // ------------------------------------------------------------ WebDAV 最小客户端
    //
    // 只用到 WebDAV 最基本的三个动作：MKCOL 建目录、PUT 上传、GET 下载。
    // 认证用 Basic（legado 的 WebDAV 配置也只有 url/username/password）。

    /** 解析 WebDAV 配置，返回 (url, username, password) */
    private fun resolveWebDav(id: String?): Triple<String, String, String>? {
        val server = id?.toLongOrNull()?.let { appDb.serverDao.get(it) }
            ?: appDb.serverDao.all().firstOrNull()
            ?: return null
        val cfg = runCatching {
            JsonParser.parseString(server.config ?: "{}").asJsonObject
        }.getOrNull() ?: return null
        val url = cfg.get("url")?.takeIf { !it.isJsonNull }?.asString ?: return null
        if (url.isBlank()) return null
        return Triple(
            url,
            cfg.get("username")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            cfg.get("password")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        )
    }

    private fun webDavAuth(user: String, pwd: String): String =
        "Basic " + java.util.Base64.getEncoder()
            .encodeToString("$user:$pwd".toByteArray(StandardCharsets.UTF_8))

    private fun webDavPut(url: String, user: String, pwd: String, body: ByteArray): Int {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder()
            .url(url)
            .put(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", webDavAuth(user, pwd))
            .build()
        client.newCall(req).execute().use { return it.code }
    }

    private fun webDavGet(url: String, user: String, pwd: String): String? {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", webDavAuth(user, pwd))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    /** 逐级 MKCOL 建目录；已存在（405）也当成功 */
    private fun ensureWebDavDir(url: String, user: String, pwd: String) {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return
        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        var path = ""
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        for (seg in segments) {
            path += "/$seg"
            val dirUrl = "${uri.scheme}://${uri.authority}$path/"
            runCatching {
                val req = okhttp3.Request.Builder()
                    .url(dirUrl)
                    .method("MKCOL", null)
                    .header("Authorization", webDavAuth(user, pwd))
                    .build()
                client.newCall(req).execute().close()
            }
        }
    }

    /**
     * 访问 Yiove 书源仓库的接口。
     *
     * 该 API 会做来源校验：不带 Referer/Origin 时只会返回站点首页（SPA 的 catch-all），
     * 看起来像"接口没数据"，实则是被挡了。所以这里固定带上来源头。
     */
    private fun fetchYiove(url: String): String {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://shuyuan.yiove.com/")
            .header("Origin", "https://shuyuan.yiove.com")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("仓库返回 HTTP ${resp.code}")
            // 被来源校验挡住时会拿到站点 HTML，给个明确提示而不是让 JSON 解析报错
            if (body.trimStart().startsWith("<!DOCTYPE") || body.contains("<div id=\"app\">")) {
                error("仓库接口拒绝了这次请求（缺少来源头）")
            }
            return body
        }
    }

    /** 组装一份备份 JSON（与 /api/backup 的内容一致） */
    private fun buildBackupJson(): ByteArray {
        val data = linkedMapOf<String, Any>(
            "version" to 1,
            "time" to System.currentTimeMillis(),
            "app" to "legado-win",
            "book_sources" to appDb.bookSourceDao.all(),
            "books" to appDb.bookDao.all(),
            "chapters" to appDb.bookChapterDao.all(),
            "book_groups" to appDb.bookGroupDao.all(),
            "replace_rules" to appDb.replaceRuleDao.all(),
            "bookmarks" to appDb.bookmarkDao.all(),
            "read_record" to appDb.readRecordDao.all(),
            "rss_sources" to appDb.rssSourceDao.all(),
            "rss_articles" to appDb.rssArticleDao.all(),
            "rss_read_record" to appDb.rssReadRecordDao.all(),
            "rss_stars" to appDb.rssStarDao.all(),
            "txt_toc_rules" to appDb.txtTocRuleDao.all(),
        )
        return gson.toJson(data).toByteArray(StandardCharsets.UTF_8)
    }

    /** 把备份 JSON 合并进本地数据（与 /api/restore 同一套逻辑） */
    private fun applyBackup(text: String): Map<String, Int> {
        val root = JsonParser.parseString(text).asJsonObject
        val counts = linkedMapOf<String, Int>()

        fun <T : Any> table(key: String, clazz: Class<T>, insert: (Array<T>) -> Unit) {
            val arr = root.getAsJsonArray(key) ?: return
            if (arr.size() == 0) return
            val type = com.google.gson.reflect.TypeToken
                .getParameterized(List::class.java, clazz).type
            val list = gson.fromJson<List<T>>(arr.toString(), type) ?: return
            // T 不是 reified，toTypedArray() 用不了，反射建数组
            @Suppress("UNCHECKED_CAST")
            val arr2 = java.lang.reflect.Array.newInstance(clazz, list.size) as Array<T>
            list.forEachIndexed { i, v -> arr2[i] = v }
            insert(arr2)
            counts[key] = list.size
        }

        table("book_sources", io.legado.app.data.entities.BookSource::class.java) {
            appDb.bookSourceDao.insert(*it)
        }
        table("books", io.legado.app.data.entities.Book::class.java) {
            appDb.bookDao.insert(*it)
        }
        table("chapters", io.legado.app.data.entities.BookChapter::class.java) {
            appDb.bookChapterDao.insert(*it)
        }
        table("book_groups", io.legado.app.data.entities.BookGroup::class.java) {
            appDb.bookGroupDao.insert(*it)
        }
        table("replace_rules", io.legado.app.data.entities.ReplaceRule::class.java) {
            appDb.replaceRuleDao.insert(*it)
        }
        table("bookmarks", io.legado.app.data.entities.Bookmark::class.java) {
            appDb.bookmarkDao.insert(*it)
        }
        table("rss_sources", io.legado.app.data.entities.RssSource::class.java) {
            appDb.rssSourceDao.insert(*it)
        }
        table("txt_toc_rules", io.legado.app.data.entities.TxtTocRule::class.java) {
            appDb.txtTocRuleDao.insert(*it)
        }
        return counts
    }

    /**
     * 把可能是相对的地址转成绝对。
     *
     * 章节 url 有时会是相对的（取决于书源的目录规则写法），
     * 只拿它当基准解析不出绝对地址；因此再拿书源地址兜一层。
     * 漫画与视频都走这里，避免两边各写一套。
     */
    private fun absolutize(raw: String?, vararg bases: String?): String? {
        val r = raw?.trim().orEmpty()
        if (r.isEmpty()) return null
        if (r.startsWith("http://", true) || r.startsWith("https://", true)) return r
        for (b in bases) {
            if (b.isNullOrBlank()) continue
            val abs = runCatching { NetworkUtils.getAbsoluteURL(b, r) }.getOrNull()
            if (!abs.isNullOrBlank() && abs != r &&
                (abs.startsWith("http://", true) || abs.startsWith("https://", true))
            ) {
                return abs
            }
        }
        return r
    }

    /**
     * 从章节内容里解析出媒体地址（音频/视频通用）。
     *
     * 覆盖三种写法：
     *   1. 内容本身就是地址
     *   2. `<audio>` / `<video>` / `<source>` 标签的 src
     *   3. 兜底：正文里第一个 http 链接
     */
    private fun extractMediaUrl(content: String, vararg bases: String?): String? {
        val text = content.trim()
        if (text.isEmpty()) return null
        // 1) 内容本身就是地址
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            return text.substringBefore('\n').trim()
        }
        // 2) 从 audio / video / source 标签取 src
        val tag = Regex("""<(?:audio|video|source)[^>]*\ssrc\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE).find(text)
        if (tag != null) return absolutize(tag.groupValues[1], *bases)
        // 3) 兜底：正文里第一个 http 链接
        val any = Regex("""https?://[^\s"'<>）)]+""").find(text)
        return any?.value
    }

    /**
     * 音频 / 视频章节的公共实现。
     *
     * 两者在引擎侧完全一致——同一个 `ruleContent`，取到内容后当播放地址用
     * （legado 的 `AudioPlay` / `VideoPlay` 也是这样）。差别只在播放器元素。
     */
    private fun serveMediaChapter(ex: HttpExchange, params: Map<String, String>, kind: String) {
        val bookUrl = params["bookUrl"] ?: ""
        val book = appDb.bookDao.getBook(bookUrl)
        if (book == null) {
            json(ex, 404, mapOf("ok" to false, "error" to "书不在书架中"))
            return
        }
        val chapters = appDb.bookChapterDao.getChapterList(bookUrl)
        val index = params["index"]?.toIntOrNull() ?: 0
        val chapter = chapters.getOrNull(index)
        if (chapter == null) {
            json(ex, 404, mapOf("ok" to false, "error" to "章节不存在，请先获取目录"))
            return
        }
        try {
            val src = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw IllegalStateException("书源不存在：${book.origin}")
            val content = runBlocking {
                withTimeout(searchTimeoutMs) {
                    WebBook.getContentAwait(
                        src, book, chapter, chapters.getOrNull(index + 1)?.url, false
                    )
                }
            }.orEmpty()
            val mediaUrl = extractMediaUrl(content, chapter.url, src.bookSourceUrl)
            json(
                ex, 200, mapOf(
                    "ok" to (mediaUrl != null),
                    "kind" to kind,
                    "index" to index,
                    "total" to chapters.size,
                    "title" to chapter.title,
                    "mediaUrl" to mediaUrl,
                    "videoUrl" to mediaUrl,   // 兼容视频页面已用的字段名
                    "prevIndex" to if (index > 0) index - 1 else -1,
                    "nextIndex" to if (index < chapters.size - 1) index + 1 else -1,
                    "error" to if (mediaUrl == null) "没能从这一章解析出播放地址" else null,
                )
            )
        } catch (e: Exception) {
            LogUtils.e("AppServer", "读取${kind}章节失败: ${e.message}", e)
            json(ex, 200, mapOf("ok" to false, "error" to (e.message ?: "读取失败")))
        }
    }

    /** 按书源类型判断这本是什么书：0 文字 / 1 音频 / 2 图片 / 3 下载 / 4 视频 */
    private fun serveBookKind(ex: HttpExchange, params: Map<String, String>) {
        val book = appDb.bookDao.getBook(params["bookUrl"] ?: "")
        val src = book?.let { appDb.bookSourceDao.getBookSource(it.origin) }
        val type = src?.bookSourceType ?: -1
        json(
            ex, 200, mapOf(
                "ok" to true,
                "sourceType" to type,
                "isAudio" to (type == io.legado.app.constant.BookSourceType.audio),
                "isManga" to (type == io.legado.app.constant.BookSourceType.image),
                "isVideo" to (type == io.legado.app.constant.BookSourceType.video),
            )
        )
    }

    private fun dictBrief(d: DictRule) = mapOf(
        "name" to d.name,
        "urlRule" to d.urlRule,
        "showRule" to d.showRule,
        "enabled" to d.enabled,
        "sortNumber" to d.sortNumber,
    )

    /** 订阅源列表项 */
    private fun rssBrief(a: RssArticle) = mapOf(
        "title" to a.title,
        "link" to a.link,
        "origin" to a.origin,
        "sort" to a.sort,
        "group" to a.group,
        "pubDate" to a.pubDate,
        "description" to a.description,
        "image" to a.image,
        "read" to a.read,
        "type" to a.type,
    )

    /** 导入订阅源（JSON 数组或单个对象） */
    private fun importRssSources(body: String): Int {
        val text = body.trim()
        if (text.isEmpty()) return 0
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return 0
        val list = when {
            root.isJsonArray -> root.asJsonArray.mapNotNull {
                runCatching { gson.fromJson(it, RssSource::class.java) }.getOrNull()
            }

            root.isJsonObject -> listOfNotNull(
                runCatching { gson.fromJson(root, RssSource::class.java) }.getOrNull()
            )

            else -> emptyList()
        }.filter { !it.sourceUrl.isNullOrBlank() }
        list.forEach {
            if (it.sourceName.isBlank()) it.sourceName = it.sourceUrl
            appDb.rssSourceDao.insert(it)
        }
        LogUtils.i("AppServer", "导入订阅源 ${list.size} 个")
        return list.size
    }

    private fun buildBook(q: Map<String, String>): Book {        val book = Book()
        book.name = q["name"]?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) } ?: ""
        book.author = q["author"]?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) } ?: ""
        book.bookUrl = q["bookUrl"] ?: ""
        book.origin = q["sourceUrl"] ?: ""
        book.originName = appDb.bookSourceDao.getBookSource(book.origin)?.bookSourceName ?: ""
        book.tocUrl = q["tocUrl"] ?: book.bookUrl
        return book
    }

    private fun importSources(body: String): Int {
        val text = body.trim()
        if (text.isEmpty()) return 0
        val root = JsonParser.parseString(text)
        val list = when {
            root.isJsonArray -> root.asJsonArray.mapNotNull { runCatching { gson.fromJson(it, BookSource::class.java) }.getOrNull() }
            root.isJsonObject -> listOfNotNull(runCatching { gson.fromJson(root, BookSource::class.java) }.getOrNull())
            else -> emptyList()
        }
        val valid = list.filter { !it.bookSourceUrl.isNullOrBlank() }
        valid.forEach {
            if (it.bookSourceName.isBlank()) it.bookSourceName = it.bookSourceUrl
            appDb.bookSourceDao.insert(it)
        }
        LogUtils.i("AppServer", "导入书源 ${valid.size} 个（解析到 ${list.size} 个）")
        return valid.size
    }

    private fun sourceBrief(s: BookSource) = mapOf(
        "url" to s.bookSourceUrl,
        "name" to s.bookSourceName,
        "group" to s.bookSourceGroup,
        "enabled" to s.enabled,
        "hasSearch" to !s.searchUrl.isNullOrBlank(),
        "hasExplore" to !s.exploreUrl.isNullOrBlank(),
    )

    private fun bookBrief(b: Book) = mapOf(
        "name" to b.name,
        "author" to b.author,
        "bookUrl" to b.bookUrl,
        "origin" to b.origin,
        "originName" to b.originName,
        "coverUrl" to b.coverUrl,
        "intro" to b.intro,
        "group" to b.group,
        "durChapterIndex" to b.durChapterIndex,
        "durChapterTitle" to b.durChapterTitle,
        "totalChapterNum" to b.totalChapterNum,
        "latestChapterTitle" to b.latestChapterTitle,
    )    // ------------------------------------------------------------ HTTP 工具
    private fun query(ex: HttpExchange): Map<String, String> {
        val raw = ex.requestURI.rawQuery ?: return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val i = part.indexOf('=')
            if (i < 0) part to "" else {
                URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8) to
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8)
            }
        }.toMap()
    }

    private fun readBody(ex: HttpExchange): String =
        ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)

    private fun json(ex: HttpExchange, code: Int, data: Any) {
        val bytes = gson.toJson(data).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun serveStatic(ex: HttpExchange, path: String) {
        val res = when {
            path == "/" || path.isEmpty() -> "/web/index.html"
            else -> "/web${path}"
        }
        val stream = javaClass.getResourceAsStream(res)
        if (stream == null) {
            val msg = "404 Not Found: $path".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(404, msg.size.toLong())
            ex.responseBody.use { it.write(msg) }
            return
        }
        val bytes = stream.use { it.readBytes() }
        ex.responseHeaders.add("Content-Type", contentType(res) + "; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun contentType(name: String): String = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8765
    AppServer(port).start()
    Thread.currentThread().join()
}
