# legado 阅读 · Windows 启动器
#
# 职责：
#   1. 在后台启动内嵌引擎服务（JVM 进程）
#   2. 等它就绪
#   3. 用**默认浏览器**打开（普通标签页，不另开独立窗口）
#   4. 关闭本启动器窗口后回收后台服务
#
# 参数：
#   -NoBrowser   只启动服务，不自动打开浏览器（自己用浏览器访问即可）
#   -Port 9000   指定端口
#   -DataDir D:\xx  指定数据目录

[CmdletBinding()]
param(
    [int]$Port = 0,                # 0 = 自动挑选可用端口
    [string]$DataDir = "",         # 数据目录，默认 <安装目录>\data
    [switch]$NoWindow,             # 只启动服务并等待，不开浏览器（脚本内调试用）
    [switch]$NoBrowser,            # 只启动服务，不自动打开浏览器
    [switch]$Console               # 保留控制台显示日志
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$runtime = Join-Path $root 'runtime'

if (-not (Test-Path (Join-Path $runtime 'lib'))) {
    Write-Host "找不到运行时目录：$runtime" -ForegroundColor Red
    Write-Host "请先在工程根目录执行： gradle :app:installDist  然后运行 tools\package.ps1" -ForegroundColor Yellow
    exit 1
}

if ([string]::IsNullOrWhiteSpace($DataDir)) {
    $DataDir = Join-Path $root 'data'
}
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

# ---------------------------------------------------------------- 选端口
function Test-PortFree([int]$p) {
    try {
        $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $p)
        $l.Start(); $l.Stop(); return $true
    } catch { return $false }
}

if ($Port -le 0) {
    $Port = 8765
    while (-not (Test-PortFree $Port)) { $Port++ }
} elseif (-not (Test-PortFree $Port)) {
    Write-Host "端口 $Port 已被占用，换一个。" -ForegroundColor Yellow
    while (-not (Test-PortFree $Port)) { $Port++ }
}

$url = "http://127.0.0.1:$Port"

# ---------------------------------------------------------------- 启动引擎服务
$java = $null
foreach ($cand in @(
        (Join-Path $env:JAVA_HOME 'bin\java.exe'),
        'java.exe')) {
    if ($cand -and (Get-Command $cand -ErrorAction SilentlyContinue)) { $java = $cand; break }
}
if (-not $java) {
    Write-Host "找不到 Java。请安装 JDK 17 或更高版本，并设置 JAVA_HOME。" -ForegroundColor Red
    exit 1
}

$libs = (Join-Path $runtime 'lib\*')
$outLog = Join-Path $DataDir 'server.log'
$errLog = Join-Path $DataDir 'server.err.log'

$javaArgs = @(
    "-Dfile.encoding=UTF-8",
    "-Dlegado.data.root=$DataDir",
    "-cp", $libs,
    'legadowin.AppServerKt',
    "$Port"
)

Write-Host "正在启动 legado 引擎服务…" -ForegroundColor Cyan
$proc = Start-Process -FilePath $java -ArgumentList $javaArgs -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog

# ---------------------------------------------------------------- 等就绪
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    if ($proc.HasExited) { break }
    try {
        $r = Invoke-WebRequest -Uri "$url/api/health" -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
}

if (-not $ready) {
    Write-Host "服务启动失败。" -ForegroundColor Red
    if (Test-Path $errLog) { Get-Content $errLog -Tail 20 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray } }
    if (-not $proc.HasExited) { $proc.Kill() }
    exit 1
}

Write-Host "服务已就绪：$url" -ForegroundColor Green
Write-Host "数据目录：$DataDir" -ForegroundColor DarkGray

if ($NoWindow) {
    Write-Host "按 Ctrl+C 停止服务。"
    try { Wait-Process -Id $proc.Id } finally { if (-not $proc.HasExited) { $proc.Kill() } }
    exit 0
}

# ---------------------------------------------------------------- 打开浏览器
if ($NoBrowser) {
    Write-Host "服务已在后台运行：$url" -ForegroundColor Green
    Write-Host "按 Ctrl+C 停止。" -ForegroundColor DarkGray
    try { Wait-Process -Id $proc.Id } finally { if (-not $proc.HasExited) { $proc.Kill() } }
    exit 0
}

# 用默认浏览器打开（普通标签页，不另开独立窗口）
Write-Host "正在用默认浏览器打开…" -ForegroundColor Cyan
try {
    Start-Process $url
} catch {
    Write-Host "无法自动打开浏览器，请手动访问：$url" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "服务运行中：$url" -ForegroundColor Green
Write-Host "关闭本窗口（或按 Ctrl+C）即停止服务。" -ForegroundColor DarkGray

try {
    Wait-Process -Id $proc.Id
} finally {
    if (-not $proc.HasExited) {
        Write-Host "正在关闭引擎服务…" -ForegroundColor DarkGray
        $proc.Kill()
        $proc.WaitForExit(5000) | Out-Null
    }
    Write-Host "已退出。" -ForegroundColor Green
}
