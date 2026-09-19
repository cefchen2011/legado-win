# legado-plus → Windows 移植工程

把 [phoenix0451/legado-plus](https://github.com/phoenix0451/legado-plus)（阅读Sigma，Android/Kotlin）
移植到 Windows 桌面。

---

## 1. 环境事实（重要，避免重复踩坑）

| 事项 | 结论 |
|---|---|
| `github.com:443` | **不通**（git clone 直接失败） |
| `codeload.github.com` | **通** —— 用 zip 直链获取源码 |
| `api.github.com` / `raw.githubusercontent.com` | 通 |
| 本机代理 `127.0.0.1:7892` | 未监听，不可用 |
| Maven（aliyun / huaweicloud / repo1 / jitpack / dl.google） | **全部可达**，Gradle 构建无网络障碍 |
| JDK | Temurin **25.0.4** |
| Gradle | **9.7.0**（全局 `gradle`，无 wrapper） |
| Kotlin | 2.3.10（与上游 `gradle/libs.versions.toml` 对齐） |
| Android SDK | 有（platforms 34/36），**无 AVD / system-image**，模拟器路线不可行 |
| Node / Python | Node 24.19.0 / Python 3.12.10 |

> **`.ps1` 必须带 UTF-8 BOM（踩过两次）。**
> PowerShell 5.1 在没有 BOM 时按本地代码页解析 UTF-8 脚本，中文会破坏语法，
> 报出误导性的 `MissingEndCurlyBrace` / "string is missing the terminator"。
> **用编辑工具改过 `.ps1` 后一定要补回 BOM**（工具会按无 BOM 写）。
> 校验：`[System.Management.Automation.Language.Parser]::ParseFile($p,[ref]$null,[ref]$err)`。
> `tools/fix_bom.py` 可批量补。

> **PowerShell 里跑 Python 别用 heredoc**（`<<'EOF'` 不支持），也别在 `python -c "..."`
> 里写带引号/`||`/`$` 的代码——会被 PowerShell 抢先解析。稳妥做法：写成 `.py` 文件再执行。

获取上游源码（`git clone` 不可用时的正确姿势）：

```powershell
curl.exe -L -o legado-plus.zip "https://codeload.github.com/phoenix0451/legado-plus/zip/refs/heads/main"
```

> 注意：`curl -C -` 续传会失败，codeload 动态打包、不支持 Range，必须一次性下完
> （约 6.9 MB / 2238 个条目，本机实测 ~40 KB/s，需 3 分钟）。

目录约定：

- 上游对照源码：`C:\workspace\legado-plus`（只读，作为移植来源）
- 移植工程：`C:\workspace\legado-win`

---

## 2. 移植架构

原版是 Android 应用：Jetpack Compose + Room + WorkManager + WebView + MediaPlayer + TTS。
整体照搬不现实，因此按**分层移植**：

```
legado-win/
├── compat/      Android API 兼容层（纯 JVM 桩）
├── engine/      移植后的 legado 引擎（保持上游包名，源码尽量不改）
└── app/         Windows 应用外壳（本地 HTTP 服务 + Web UI）  ← 待建
```

核心判断依据：**legado 的价值在书源规则引擎**（CSS/XPath/JSONPath/正则/JS 解析规则），
这部分与 Android 耦合极浅，可以原样移植；UI/存储/播放等应用层由 Windows 端重写。

### 2.1 compat —— 让上游源码不改动就能编译

`tools/gen_android_stubs.py` 生成 36 个纯 JVM 桩，覆盖引擎实际用到的全部 Android API：

- `android.text.TextUtils`、`android.util.Base64`、`android.util.Log`：**真实实现**
- `androidx.room.*`（18 个注解）、`androidx.annotation.Keep`、`kotlinx.parcelize.Parcelize`：空壳
- `android.os.Parcelable`：**空标记接口**（不要求实现任何方法，上游实体类可原样编译）
- `androidx.collection.LruCache`：**真实 LRU 实现**

实测整个 `analyzeRule` 包只依赖 5 个 Android 符号，全部已覆盖。

### 2.2 引擎边界原则

| 类别 | 处理方式 |
|---|---|
| 纯逻辑（规则解析、实体、工具） | **原样移植** |
| 依赖 Android API 的应用层（UI / Service / DAO / Glide / ExoPlayer / WebView） | **排除**，桌面端另写 |
| 被核心引用但必须重写的少数类（`Debug`/`AppConfig`/`CacheManager`/`BackstageWebView`） | 写桌面实现，**保持上游签名**，使核心文件零改动 |

---

## 3. 工具链（`tools/`）

| 脚本 | 作用 |
|---|---|
| `gen_android_stubs.py` | 生成 compat 兼容层 |
| `port_sync.py <上游相对路径>...` | 从上游拷贝源文件到 engine，保持包路径 |
| `closure.py [--dry]` | **自动补齐依赖闭包**：扫描 import，按受控排除策略定位并同步缺失文件，循环至收敛 |
| `prune.py [--apply]` | 按 Android 耦合度裁剪：剔除真正依赖 Android API 的文件 |
| `errstat.py <编译日志>` | 编译错误按文件 / 类型统计 |
| `report.py <日志> <输出>` | 生成移植进度与边界报告 |

> ⚠️ 所有脚本**不要**用 PowerShell 的 `Get-Content/Set-Content` 改写（会把中文变乱码）。
> 运行 Python 时用 `python -X utf8`，并先设 `[Console]::OutputEncoding=[Text.Encoding]::UTF8`。

> ⚠️ **抓编译日志必须走 cmd 原生重定向**，否则 Kotlin 的 ANSI 着色会让
> PowerShell 管道丢掉错误里的符号名，看到的全是 `Unresolved reference `（后面空白）：
>
> ```powershell
> $env:NO_COLOR='1'
> cmd /c "gradle :engine:compileKotlin --console=plain --no-daemon > build_err.txt 2>&1"
> ```

---

## 4. 当前状态

### ✅ 里程碑：端到端可用

```
gradle build          -> BUILD SUCCESSFUL
gradle :app:run       -> http://127.0.0.1:8765  （Windows 应用外壳）
```

**完整链路已在浏览器中实测通过**：搜索 → 详情 → 目录 → 正文。


```
gradle clean build    -> BUILD SUCCESSFUL（全量干净构建）
pwsh tools\package.ps1 -> dist\（15.5 MB，可整体拷走）
双击 dist\legado-win.cmd 启动
```

**全新状态端到端实测**（`-DataDir` 指向空目录）：

| 步骤 | 结果 |
|---|---|
| 空库启动 | `{"sources":0,"books":0}` |
| 搜索「测试」 | 2 本书 |
| 目录 | 3 章：第一章 百世书 / 第二章 顺天易 / 第三章 魔门人材 |
| 正文 | 标题「第二章 顺天易」+ 正确正文（含段落缩进） |

### 工程构成

| 模块 | 内容 | 文件数 |
|---|---|---:|
| `compat` | Android API 兼容层（纯 JVM 实现） | 66 |
| `engine/src/main` | **原样移植的上游 legado 源码** | 254 |
| `engine/src/desktop` | 桌面端手写替代实现 | 41 |
| `app` | 应用外壳 + Web UI | 4 |
| `tools` | 移植工具链与打包脚本 | 12 |

发行版结构：

```
dist/
├── legado-win.cmd          双击启动（带控制台，便于看日志）
├── legado-win-silent.vbs   双击启动（无控制台）
├── launcher.ps1            启动逻辑
├── runtime/lib/*.jar       应用与全部依赖
├── data/                   书源、书架、缓存、日志（可随包迁移）
└── 说明.txt
```

启动器做的事：后台拉起引擎服务 → 轮询 `/api/health` 等就绪 →
用**默认浏览器**打开（普通标签页）→ 关闭启动器窗口时回收服务进程。

> 打包时**会保留已有的 `data/` 目录**，重新打包不会弄丢书源、书架与阅读进度。
> 数据目录可用 `-DataDir` 指定，因此可以随包放在 U 盘里带走。

### Windows 应用外壳（`app` 模块）

架构：**JVM 内嵌 HTTP 服务 + Web UI**，引擎直接跑在进程里，不需要额外运行时。
HTTP 服务用 JDK 自带的 `com.sun.net.httpserver`，零新增依赖。

| 接口 | 说明 |
|---|---|
| `GET /api/health` | 健康检查 |
| `GET/POST/DELETE /api/sources` | 书源列表 / 导入 / 删除 |
| `GET /api/source/toggle` | 启用停用书源 |
| `GET /api/search?key=` | 按关键词并发搜索全部启用书源（含整批预算） |
| `GET /api/search/stream?key=` | **流式搜索（SSE）**：哪个源先返回就先推送哪个源的结果 |
| `GET /api/change-source/stream` | **换源（SSE）**：在其他书源里精确匹配同一本书（书名+作者一致） |
| `POST /api/change-source/apply` | 切换书源：更新来源与书址，保留进度，客户端重新取目录 |
| `GET /api/bookinfo` | 书籍详情（`ruleBookInfo`） |
| `GET /api/toc` | 目录（未给 tocUrl 时先取详情解析，与 legado 真实流程一致） |
| `GET /api/content?index=` | 正文（`ruleContent`，自动应用净化规则） |
| `GET/POST /api/progress` | 阅读进度读写（续读） |
| `GET/POST /api/config` | 阅读设置（走引擎的 `ReadBookConfig`） |
| `GET/POST /api/replace` | 替换净化规则增删改查 |
| `GET/POST /api/bookmark` | 书签增删查（按书过滤） |
| `GET /api/history` | 阅读历史（累计时长 + 最近阅读时间） |
| `GET/POST /api/rss/sources` | 订阅源列表 / 导入 |
| `GET /api/rss/sorts` | 订阅分类（同样兼容新版 JSON 与旧版 `标题::地址`） |
| `GET /api/rss/articles` | 某个订阅分类下的文章 |
| `GET /api/rss/content` | 文章正文（走 `ruleContent`） |
| `POST /api/rss/read` | 标记已读 |
| `GET/POST /api/groups` | 书架分组：列表（含各组件数）/ 新建 / 改名 |
| `POST /api/groups/delete` | 删除分组（组内书籍**回落到未分组**，不跟着消失） |
| `POST /api/book/group` | 把书移动到指定分组 |
| `POST /api/local/import?path=` | 导入本地 TXT（分章走移植的 `TextFile`） |
| `GET /api/local/scan?dir=` | 扫描目录下的本地书文件 |
| `GET /api/local/toc` / `local/content` | 本地书的目录与正文 |
| `GET /api/sources/export` | 导出全部书源（JSON 下载，可备份/迁移） |
| `GET /api/export?bookUrl=` | 导出整本书为 TXT（在线书逐章抓取；本地书直接读） |
| `GET /api/debug/stream` | **书源调试（SSE）**：实时推送引擎 142 个 `Debug.log` 调用点的输出 |
| `GET /api/manga/content` | **漫画阅读**：抽出章节里的图片地址（复用上游 `BookHelp.flowImages`） |
| `GET /api/manga/check` | 按书源 `bookSourceType` 判断是否漫画书 |
| `GET/POST /api/theme` | 当前生效的配色（引擎 `ThemeConfig` 的读写） |
| `GET/POST /api/themes` + `/apply` + `/delete` + `/snapshot` | **命名主题**：预设 + 自定义主题的增删切换 |
| `GET /api/video/content` | **视频播放**：解析章节里的播放地址（书源类型 4） |
| `GET /api/audio/content` | **音频播放**：同上（书源类型 1），与视频共用实现 |
| `GET /api/book/kind` | 一次判定书的类型（文字/音频/图片/视频），前端据此选阅读器 |
| `GET/POST /api/dict` + `/api/dict/query` | **词典查询**：复用引擎的 `AnalyzeUrl` + `AnalyzeRule` |
| `GET/POST /api/webdav` + `/backup` + `/restore` | **WebDAV 同步**（MKCOL / PUT / GET 三个动作） |
| `GET /api/store/search` | **书源仓库搜索**（Yiove）；空关键词时改为列出全部书源 |
| `POST /api/store/import` | 从书源仓库导入单个书源或整个合集 |
| `GET /api/sources/check/stream` | **批量校验书源（SSE）**：逐源试探并实时回报可用/可疑/失效 |
| `POST /api/sources/batch` | 批量停用 / 启用 / 删除书源 |
| `GET /api/backup` / `POST /api/restore` | 备份与恢复用户数据（合并语义） |
| `GET /api/explore?sourceUrl=` | 发现页分类（兼容新版 JSON 与旧版 `标题::地址` 两种格式） |
| `GET /api/explore/books` | 某个发现分类下的书籍 |
| `GET/POST /api/shelf` | 书架 / 加书 / 移除 |

前端（`app/src/main/resources/web/`）：书架 / 发现 / 搜索 / 书源 / 净化 / **订阅** / 历史 七个视图 +
阅读器（目录抽屉、书签、**朗读**、阅读设置、明亮·护眼·夜间三主题）。

本地书导入支持 **TXT 与 EPUB**：

| 格式 | 实现 |
|---|---|
| TXT | 移植的 `TextFile` + 官方 26 条分章正则（`defaultData/txtTocRule.json`） |
| EPUB | 移植的 `EpubFile` + `modules/book` 的 epublib（69 个 Java 文件） |

> **订阅源有两个易混字段**：`ruleArticles` 是从列表页提取条目的规则，
> `ruleContent` 是从文章页提取正文的规则。标准 RSS 源两者都不需要
> （走 `RssParserDefault`）；我一开始把 `id.content@html` 写进了 `ruleArticles`，
> 结果条目数为 0——标准 RSS 被当成了 HTML 规则源。修正后才正常。

> **EPUB 分章不是简单按行切**：legado 的启发式要求相邻章节标题之间> **至少 100 字**（`csNum >= numE * 3`），否则视为误识别的"卷"而弃用该规则。
> 我一开始用短文本测试，只识别出 1 章，排查后确认**是测试数据不真实而非移植有误**——
> 这条设计是合理的：卷标题与章节标题形态相近，没有内容长度约束会大量误判。

> **朗读为什么不做在引擎里**：Windows 自带 SAPI 与 Edge 的中文语音质量都很好，
> 而浏览器有现成的 `speechSynthesis` —— 逐段朗读、暂停/续读、语速、音色全部免费，
> 且不需要引入任何后端依赖。实测可用 324 个语音、其中 17 个中文
> （含 `Microsoft 晓晓 Online (Natural)` 自然音色）。
> 逐段而不是整章丢进去，是因为 Chromium 对超长 utterance 有截断问题，
> 顺便还能高亮当前朗读的段落，接近 legado 的"边听边看"。

> **搜索做成流式（SSE）而不是等全部完成**：这是本轮最重要的修正，也是手机端的做法。
>
> 导入真实书源后会发现：总有几个源因为被墙、DNS 解析失败或站点挂掉而长时间不返回，
> 而 **DNS 解析这类阻塞在 JVM 上是不可中断的**——`withTimeout` 取消不了它。
> 于是：
> 1. 只给每个源加超时不够，整批仍会无限等待；
> 2. 于是再加一层整批预算（45s），到点返回已完成的部分；
> 3. 但 `runBlocking` 会**等待所有子协程**，卡住的协程取消不掉，`done` 事件仍然发不出去。
>
> 最终解法：搜索任务跑在**独立 CoroutineScope** 里（不 join），HTTP 处理线程只等预算，
> 每完成一个源就推一个 SSE 事件。实测 14 个源、45s 预算、13 个按时返回、
> 干净地发出 `done` 收尾。
>
> 顺带把失败源和原因也推给前端——用户能立刻分清"网络不通"和"书源规则失效"。

> **真实书源实测结果**（22 个公开书源，搜索「诡秘之主」）：
> 「阅友小说」返回 **5 本真实书籍**（诡秘档案 / 太初之主 / 万夜之主 …），
> 带封面、作者、分类与简介——**移植后的规则引擎成功解析了真实网站的搜索规则**。
> 其余源的状态也很明确：2 个是 DNS 解析失败、1 个连接被拒、其余连接成功但无匹配。

### ✅ 完整真实链路验证（真实网站、真实小说）

用「阅友小说」（`m.suixkan.com`）单源走完整流程：

| 环节 | 结果 |
|---|---|
| 搜索「诡秘之主」 | 0.6s 返回 **5 本真实书籍**，带真实书籍 URL |
| 书籍详情 | 正确解析出 `tocUrl` |
| 目录 | **559 章**，标题为真实章节名（第1章青念与叶倾雪 / 第2章叶夕雪 …） |
| 正文 | 第 3 章「筑基」，**3112 字真实正文**，段落缩进由引擎排版 |
| 界面 | 阅读器正常显示「太初之主 · 枯三生」，可翻章、可朗读、可加书签 |

这意味着：**书源规则（搜索/详情/目录/正文四类规则）在真实网站上全部生效**，
不是只能跑通简单的示例。这是「legado 确实被移植过来了」最直接的证据。

> **本地书导入支持 TXT 与 EPUB**：TXT 走移植的 `TextFile`（26 条官方分章正则），
> EPUB 走移植的 `EpubFile` + `modules/book` 的 epublib（69 个 Java 文件原样可用，
> 其 Android 依赖恰好全在 compat 层覆盖范围内）。

## 5. 经验与踩坑

### 5.1 单页应用（SPA）不能直接当订阅源

用户给了一个订阅源 `https://shuyuan.yiove.com`（Yiove 书源仓库），导入成功但取文章报错：

```
Attr.value missing f. crossorigin (position:START_TAG
<script type='module' crossorigin='欢迎来到我们的书源整合网站…'>)
```

排查发现该站点是 **Vue 单页应用**：

```html
<body>
  <div id="app"></div>
  <script type="module" src="/assets/index-D2Qpc1Sp.js"></script>
</body>
```

文章列表由 JS 动态渲染，**静态 HTML 里一个字都没有**；加上该源没有任何规则
（`ruleArticles` 等全空）+ `singleUrl: true`，引擎按「标准 RSS」解析，自然失败。

**在手机端同样跑不通**，不是移植问题。

从 SPA 的 JS 包里能找到后端地址（`VITE_API_BASE_URL`），据此拼一个可用版本：

```json
{
  "sourceUrl": "https://shuyuan-api.yiove.com/shuyuan/book-source-collections",
  "singleUrl": true,
  "ruleArticles": "$.items",
  "ruleTitle": "$.name",
  "ruleDescription": "$.description",
  "ruleLink": "@js:'https://shuyuan-api.yiove.com/shuyuan/book-sources?collection_id='+result.id",
  "ruleContent": "$.items[*].name"
}
```

实测取到 10 条合集，正文列出该书源名——**JSONPath 规则、`@js:` 拼接规则、正文提取全部生效**。

教训：**判断一个"订阅源"能不能用，先看它是 RSS/接口还是网页**。
网页型需要配 HTML/JSON 规则，纯前端渲染的站点则必须找到它的后端接口。

**后来发现这个站点真正的价值是"书源仓库"**——从 SPA 的 JS 里挖到全部接口：

| 接口 | 用途 |
|---|---|
| `/shuyuan/search?search_key=&search_type=` | 搜索（`search_type` 只接受 `book-sources` / `book-source-collections`） |
| `/shuyuan/book-sources?page=&page_size=` | 列出全部书源 |
| `/import/book-source/<id>` | 取单个书源的 JSON |
| `/import/book-source-collection/<id>` | 取整个合集的书源 JSON |

**关键坑：这个 API 会校验来源。** 不带 `Referer` / `Origin` 时，它只返回站点首页
（SPA 的 catch-all），HTTP 200、有内容，看起来像"接口没数据"，实则被挡了。
加上来源头后立刻返回真实 JSON。所以 `fetchYiove()` 里固定带上这两个头。

另一个小坑：搜索接口要求 `search_key` **非空**（空串返回 422）。
因此没给关键词时改走"列出书源"接口——语义上也更合理。

**效果实测**（导入后搜「诡秘之主」）：

```
28 个源参与 · 27 个响应 · 11 个有结果 · 共 168 本
影书小说[修复] 50 条 · 笔趣阁[biqusa] 50 条 · 精华书阁 18 条
看书网 14 条 · 笔趣阁·恋爱呀 13 条 · 阅友小说 5 条 …
```

对比：导入书源仓库之前，整批书源里只有 1 个能搜出结果。

### 5.2 书源规模化后会暴露的两个问题

书源从 38 个变成 1000 个（导入「星辰整理书源合集」970 条实测）后，两个问题才显现：

**① 列表不可用** —— 1000 条平铺出来既找不到也卡。加了分组筛选（24 个分组带计数）、
名称/地址关键词筛选（带防抖），以及渲染上限 300 条。批量启用/停用/删除**只作用于当前筛出的那批**，
所以"按分组清理"是可行的工作流。

**② 搜索变成"随机的一部分源"** —— 这是更隐蔽的问题。45s 预算根本跑不完 1000 个源，
最终参与的是**碰巧先被调度到的那批**，而用户完全看不出来，会以为"就这几个源有这本书"。

修法：单次搜索限制 `maxSearchSources = 150`，并在 SSE 的 `start`/`done` 里带上
`skipped`，前端如实显示「另有 844 个书源未参与」。宁可说清楚，也不要给一个看起来完整、
实则残缺的结果。

实测（1000 个源，上限 150）：`149/150 响应 · 2365 个结果 · 45s`。

**③ 但"哪 150 个"是任意的** —— 这才是真正要解决的问题。上限只是缓解，
用户依然无法控制搜哪些源。最终解法是**按分组搜索**：

- 搜索范围选择器改成 `<optgroup>` 按分组归类，并在顶部提供「整组」入口
  （整包导入后平铺 1000 个 `<option>` 同样没法用）
- 后端支持 `group=` 参数；**用户显式选了分组或单个源时不设上限**（他是有意缩范围的）

效果对比（关键词「诡秘之主」）：

| 范围 | 源数 | 结果 | 耗时 |
|---|---:|---:|---:|
| 全部（有上限） | 150 | 2365 | 45.0s |
| 整组「听书源」 | 24 | 185 | **1s** |
| 整组「漫画源」 | 35 | 192 | 6.7s |
| 整组「视频源」 | 4 | 29 | 3.1s |

**整组搜索比全量搜索快一个数量级，结果还更聚焦**——用户想听有声书就直接选「听书源」。

**④ 结果里同一本书重复十几次** —— 整组搜「听书源」拿到 185 条，但其实是同一批书被不同源各返回一遍。
纯平铺的话用户要翻很久才能看出"这几条其实是同一本"。

修法：**按「书名 + 作者」聚合**，同一本书只出一行，标出几个源可用，点「换源」展开挑具体源。
前端维护 `state.searchMap`，每批结果合并进去后**节流重排**（350ms），
否则 150 批结果会触发 150 次全量重排。

实测：`185 条原始结果 → 41 本不重复的书`，其中 28 本有多个源可选。

另：后端搜索会跳过没有 `searchUrl` 的源——它搜了也必然空手，占掉一个并发位不值得。

### 5.3 媒体资源必须支持 HTTP Range，否则浏览器不播

音频书上线上后，`<audio>` 一直停在 `readyState: 0` / `networkState: 3`
（`NETWORK_NO_SOURCE`），而同一个地址用 `fetch` 拿到的字节完全正常：
RIFF/WAVE/fmt/data 头齐全、16kHz/16bit/单声道、64044 字节。

原因不是文件，是**响应缺少 Range 支持**。浏览器的媒体栈靠 `Range` 请求起播与拖动进度，
服务端只回 200 不支持分段时，元素会直接判定"没有可用源"而不播放。
**图片和文本不受影响**，所以这个问题只在音视频上暴露。

修法是给媒体响应加上 `Accept-Ranges: bytes` 并处理 `Range` 头（回 206 + `Content-Range`）。
修完后 `readyState: 4`、`duration: 2`，正常播放。

### 5.4 主题系统要统一，不能两套并存

最初我做了两套主题机制：一套「明亮/护眼/夜间」靠 `data-theme` 切 CSS 预设变量，
一套自定义配色靠内联 CSS 变量。两者会**互相覆盖**——切了内置主题，自定义配色还在；
改了自定义色，内置主题又不生效。

最终统一成一套：内置那三套降级为**预设主题**进入同一列表，`data-theme` 只保留昼夜标记。
配色存在引擎的 `ThemeConfig` 里（不是前端私有状态），所有界面共用同一份颜色。

### 5.5 兼容层要"真实现"而不是空壳

`android.system.Os` 最初我只写了空壳（`read` 返回 0、`lseek` 不做任何事），
编译全过、TXT 也正常，但 **EPUB 一导入就报 `entries is null`**。

原因：epublib 的 `AndroidZipFile` 是**直接基于文件描述符的 zip 读取器**
（不是 `java.util.zip.ZipFile`），它通过 `Os.read/lseek/fstat` 访问文件。
空壳实现让它读不到任何字节，于是 zip 中央目录解析失败。

修法是在 `ParcelFileDescriptor` 里维护 `FileDescriptor -> RandomAccessFile`
登记表，让 `Os.read/lseek/fstat` 真正落到文件上。这条经验值得记住：
**凡是上游代码会真正调用的系统调用，兼容层不能只满足编译**。

### 5.6 兼容层类型必须与 Kotlin 实参类型一致（重要经验）

两个系统性 bug，都是"Java 桩的类型与上游 Kotlin 实参不匹配"造成的，
合计消掉 **81 个编译错误**：

| 桩 | 错误写法 | 正确写法 | 影响 |
|---|---|---|---|
| `androidx.room.ColumnInfo.defaultValue` | `int` | `String` | 所有实体类的 `@ColumnInfo(defaultValue = "")`，71 个错误 |
| `androidx.annotation.IntDef.value` | `long[]` | `int[]` | `@IntDef(value = [常量...])`（Int 常量），10 个错误 |

> 关键点：Kotlin **不会**把 Int 常量自动加宽为 Long 传给 Java 注解，
> 而这些注解 RetentionPolicy 是 SOURCE/CLASS，运行期根本不读取，
> 因此按 Kotlin 实参的实际类型来声明即可，不影响语义。

同理，`BackstageWebView` 的 `headerMap` 必须是 `HashMap` 而非 `Map`、
`getStrResponse()` 必须是 `suspend`，否则上游调用点无法编译。

### 5.7 Gson 与 java.time：一个"能用但重启就没了"的隐蔽 bug

桌面存储层用 JSON 落盘，必须使用 `DesktopJson.gson`（注册了 `LocalDate` /
`LocalDateTime` / `Instant` 适配器）。原因是 legado 实体里有 java.time 字段
（`Book.config.startDate` 是 `LocalDate`），而 **Gson 默认靠反射读写字段，
在 JDK 9+ 模块系统下会直接失败**：

```
Failed making field 'java.time.LocalDate#year' accessible
```

要命的是两点：

1. 失败发生在**构造适配器**阶段——JSON 里根本没有该字段也会炸；
2. 表数据先写内存 map 再落盘，所以**阅读、翻页全都正常**，
   只有重启后数据不见了。

我一开始把落盘放在 `runCatching` 里静默吞异常，导致这个 bug 拖了两轮才暴露。
现在 `JsonTable.save()` 失败会显式报错，`AppServer` 写书架失败也会留痕。

---

## 6. 下一步

1. 清零剩余 97 个编译错误（长尾）：`searchBookDao`、`BookHelp.saveContent`、
   `SearchScope`、`exploreInfoMapList`、桌面文件层 `FileDoc`、`Coroutine` 取消异常
2. **引擎首次可运行**：写一个最小驱动，用真实书源跑通 搜索 → 详情 → 目录 → 正文
3. 建 Windows 应用外壳（本地 HTTP 服务 + Web UI 承载）
4. 书架、阅读设置、TTS
