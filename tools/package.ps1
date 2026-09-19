# 打包 legado 阅读 · Windows 发行版
#
# 产物结构（dist/ 目录，可整体拷走）：
#   legado-win.cmd          双击启动（带控制台）
#   legado-win-silent.vbs   双击启动（无控制台）
#   launcher.ps1            启动逻辑
#   runtime/                应用与全部依赖 jar
#   data/                   用户数据（书源、书架、缓存、日志）
#
# 用法：pwsh tools\package.ps1
[CmdletBinding()]
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $root 'dist'
$install = Join-Path $root 'app\build\install\app'

if (-not $SkipBuild) {
    Write-Host "构建发行包…" -ForegroundColor Cyan
    Push-Location $root
    try {
        & gradle ':app:installDist' --console=plain --no-daemon -q
        if ($LASTEXITCODE -ne 0) { throw "gradle :app:installDist 失败" }
    } finally { Pop-Location }
}

if (-not (Test-Path (Join-Path $install 'lib'))) {
    throw "找不到构建产物：$install"
}

Write-Host "组装 dist/…" -ForegroundColor Cyan

# 保护用户数据：重新打包不能把已有的书源/书架/阅读进度弄丢
$dataKeep = Join-Path $dist 'data'
$dataBackup = Join-Path $env:TEMP ('legado-win-data-' + [guid]::NewGuid().ToString('N'))
$hadData = Test-Path $dataKeep
if ($hadData) {
    Write-Host "  保留现有数据目录（书源/书架/进度）…" -ForegroundColor DarkGray
    Move-Item $dataKeep $dataBackup -Force
}

if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dist | Out-Null

if ($hadData) { Move-Item $dataBackup $dataKeep -Force }

# 运行时（应用 + 依赖）
Copy-Item (Join-Path $install 'lib') (Join-Path $dist 'runtime\lib') -Recurse -Force
New-Item -ItemType Directory -Force -Path (Join-Path $dist 'runtime\bin') | Out-Null

# 启动器
foreach ($f in 'launcher.ps1', 'legado-win.cmd', 'legado-win-silent.vbs') {
    Copy-Item (Join-Path $PSScriptRoot "launcher\$f") (Join-Path $dist $f) -Force
}

# 说明文件
$readme = @'
legado 阅读 · Windows
=====================

启动
----
双击 legado-win.cmd         启动（显示启动过程）
双击 legado-win-silent.vbs  启动（无控制台窗口）

启动后会自动用默认浏览器打开界面（普通标签页）。


第一次使用
----------
这个应用本身不含书内容，书来自「书源」——描述某个网站怎么搜书、取目录、取正文的规则。

  1. 打开「书源」页 → 点「书源仓库」
  2. 搜索书名/站点，或切到「合集」挑一个整包，点「导入」
  3. 回到「搜索」页输入书名，结果会随书源陆续出现
  4. 点「阅读」开始看，点「加入书架」收藏

也可以从别人给的 JSON 导入书源（「书源」页粘贴后点「导入书源」），
或直接看本地书：「＋ 导入本地 TXT」支持 TXT 与 EPUB。

书架空的时候，界面上也会显示这份引导。


能做什么
--------
看书      文字书源 / 本地 TXT / 本地 EPUB / RSS 订阅
看漫画    图片类书源自动识别，竖向滚动、懒加载
看视频    视频类书源自动识别，播放完自动下一集
听有声书  音频类书源自动识别，播放完自动下一集
朗读      浏览器内置语音（逐段朗读、当前段高亮、读完自动下一章）
词典      正文里选中词即可查
搜索      多个书源并发搜索，哪个先返回就先显示哪个的结果
换源      当前书源慢或失效时，一键切到别的有同一本书的书源
管理      书架分组、替换净化、导出书籍、书源调试
数据      本地备份恢复、WebDAV 同步
外观      主题编辑器（配色可自定义，日间夜间各一套）


数据目录：data\
  ├─ db\          书源、书架、进度、书签等（JSON 表）
  ├─ prefs\       阅读设置与配色
  ├─ cache\       正文缓存与封面
  └─ server*.log  运行日志

重新打包不会清空 data\，整个 dist 目录可以直接拷到别的机器用。


命令行参数（launcher.ps1）
  -Port 9000      指定端口（默认自动挑选 8765 起的空闲端口）
  -DataDir D:\xx  指定数据目录
  -NoBrowser      只启动服务，不自动开浏览器


环境要求
  需要 Java 17 或更高版本（可用 JAVA_HOME 指定 JDK 目录）。
  运行日志在 data\server.log 与 data\server.err.log。
'@
$readme | Set-Content -Path (Join-Path $dist '说明.txt') -Encoding UTF8

$size = [math]::Round(((Get-ChildItem $dist -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB), 1)
Write-Host ""
Write-Host "打包完成：$dist  （$size MB）" -ForegroundColor Green
Get-ChildItem $dist | ForEach-Object { Write-Host "  $($_.Name)" -ForegroundColor DarkGray }
