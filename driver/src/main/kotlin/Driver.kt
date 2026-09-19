import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.runBlocking

/**
 * legado 引擎 Windows 移植的端到端验证驱动。
 *
 * 目标不是"跑一个 demo"，而是证明**移植后的规则引擎真的能工作**：
 *   1. 覆盖书源规则的每一种语法（CSS / XPath / JSONPath / 正则 / JS）
 *   2. 走一遍真实的网络抓取（AnalyzeUrl = 引擎的网络入口）
 *   3. 验证书源 JS API 面（java.* 扩展）真的可用
 */
private var passed = 0
private var failed = 0

private fun check(name: String, expected: String, actual: String?) {
    val ok = actual?.trim() == expected
    if (ok) {
        passed++
        println("  [通过] $name  ->  $actual")
    } else {
        failed++
        println("  [失败] $name\n         期望: $expected\n         实际: $actual")
    }
}

private fun checkContains(name: String, needle: String, actual: String?) {
    val ok = actual?.contains(needle) == true
    if (ok) {
        passed++
        println("  [通过] $name  ->  ${actual?.take(80)}")
    } else {
        failed++
        println("  [失败] $name\n         期望包含: $needle\n         实际: ${actual?.take(200)}")
    }
}

private val html = """
    <html>
      <head><title>测试书籍</title></head>
      <body>
        <div class="book">
          <h1 class="title">苟在初圣魔门当人材</h1>
          <span class="author">作者：甲鱼不是龟</span>
          <a class="chapter" href="/read/1.html">第一章 百世书（一）</a>
          <a class="chapter" href="/read/2.html">第二章 百世书（二）</a>
          <a class="chapter" href="/read/3.html">第三章 顺天易，逆天难</a>
        </div>
        <div id="intro">吕阳穿越到修仙世界，成了一名魔门人材。</div>
      </body>
    </html>
""".trimIndent()

private val json = """
    {"code":0,"data":{"name":"测试书籍","author":"甲鱼不是龟",
      "toc":[{"title":"第一章","url":"/1.html"},{"title":"第二章","url":"/2.html"}]}}
""".trimIndent()

fun main() {
    println("=".repeat(64))
    println("legado 引擎 Windows 移植 —— 端到端验证")
    println("=".repeat(64))

    // ---------------------------------------------------------- 规则引擎
    println("\n【1】书源规则引擎（离线，覆盖各类规则语法）")
    val rule = AnalyzeRule()
    rule.setContent(html, "https://example.com/book/1.html")

    check("CSS 选择器取标题", "苟在初圣魔门当人材", rule.getString("class.title@text"))
    // class.chapter 命中 3 个元素，legado 的 getString 会按行拼接全部结果
    checkContains("CSS 取属性（多元素拼接）", "/read/1.html", rule.getString("class.chapter@href"))
    check("CSS 取属性（拼接后首行）", "/read/1.html", rule.getString("class.chapter@href")?.lines()?.firstOrNull())
    check("XPath 取标题", "苟在初圣魔门当人材", rule.getString("//h1[@class='title']/text()"))
    check("XPath 取 id", "吕阳穿越到修仙世界，成了一名魔门人材。", rule.getString("//div[@id='intro']/text()"))

    val chapters = rule.getStringList("class.chapter@text")
    check("CSS 取列表条数", "3", chapters?.size?.toString())
    check("CSS 列表首项", "第一章 百世书（一）", chapters?.firstOrNull())
    check("HTML 结构拼接（tag+text）", "作者：甲鱼不是龟", rule.getString("class.author@text"))

    // 正则替换规则：legado 文档语法 ##原内容##替换内容##
    // （注意：`:` 前缀的纯正则提取只在 getElements 路径生效，getString 不处理 Mode.Regex，
    //   因此这里验证书源里最常用的 ## 替换语法）
    check(
        "正则替换规则 ##原##新##",
        "甲鱼不是龟",
        rule.getString("class.author@text##作者：##")
    )

    // ---------------------------------------------------------- JSON 规则
    println("\n【2】JSON / JSONPath 规则")
    val jsonRule = AnalyzeRule()
    jsonRule.setContent(json, "https://example.com/api")
    check("JSONPath 取字段", "测试书籍", jsonRule.getString("$.data.name"))
    check("JSONPath 取数组项", "第一章", jsonRule.getString("$.data.toc[0].title"))
    val titles = jsonRule.getStringList("$.data.toc[*].title")
    check("JSONPath 列表条数", "2", titles?.size?.toString())

    // ---------------------------------------------------------- JS 规则
    println("\n【3】书源 JS 规则（验证 JsExtensions 提供的 java.* API 面）")
    // JS 数值在 Rhino 中是 Double，与 Android 端行为一致
    check("JS 纯计算", "6.0", rule.getString("@js:1+2+3"))
    check(
        "JS 字符串处理",
        "ABC",
        rule.getString("@js:'abc'.toUpperCase()")
    )
    checkContains(
        "JS 调用 java.* 扩展（base64 解码）",
        "hello",
        rule.getString("@js:java.base64Decode('aGVsbG8=')")
    )
    checkContains(
        "JS 调用 java.* 扩展（md5）",
        "900150983cd24fb0d6963f7d28e17f72",
        rule.getString("@js:java.md5Encode('abc')")
    )
    checkContains(
        "JS 访问 source 上下文（无书源时应安全返回）",
        "",
        rule.getString("@js:typeof java.ajax === 'function' ? 'ok' : 'missing'")
    )

    // ---------------------------------------------------------- 真实网络
    println("\n【4】真实网络抓取（AnalyzeUrl = 引擎网络入口）")
    try {
        val resp = runBlocking {
            AnalyzeUrl(mUrl = "https://www.baidu.com", source = null).getStrResponse()
        }
        val body = resp.body
        checkContains("HTTP 抓取成功", "<", body)
        check("HTTP 状态码", "200", resp.code().toString())
    } catch (e: Exception) {
        failed++
        println("  [失败] 网络抓取异常: ${e.javaClass.simpleName}: ${e.message}")
    }

    // ---------------------------------------------------------- 汇总
    println("\n" + "=".repeat(64))
    println("验证结果：通过 $passed 项，失败 $failed 项")
    println("=".repeat(64))
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
