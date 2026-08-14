# =============================================================================
# start-dev.ps1 — 本地同时启动 Spring Boot 后端 + Vue 前端
# =============================================================================
#
# 用法（在项目根目录 PowerShell 中）：
#   .\start-dev.ps1                  # 各开一个新窗口，同时启动前后端
#   .\start-dev.ps1 -BackendOnly     # 只启动后端
#   .\start-dev.ps1 -FrontendOnly    # 只启动前端
#   .\start-dev.ps1 -Here            # 不新开窗口：后端前台跑，前端另开窗口
#
# 地址：
#   后端 API     http://localhost:8081/api
#   健康检查     http://localhost:8081/api/actuator/health
#   前端页面     http://localhost:5173
#
# 前置依赖（需已在本机运行，脚本不会启动它们）：
#   PostgreSQL  localhost:55432  （见 docker-compose.yml）
#   Neo4j       localhost:7687   （图谱功能；KG 关闭时也可启动）
#   Node.js     >= 20.19
#   JDK         21（Maven Wrapper 会自动下载 Maven）
#
# =============================================================================

[CmdletBinding()]
param(
    # 只启动 Spring Boot 后端
    [switch]$BackendOnly,
    # 只启动 Vite 前端
    [switch]$FrontendOnly,
    # 在当前窗口前台跑后端（方便看日志）；前端仍会另开窗口
    [switch]$Here
)

$ErrorActionPreference = "Stop"

# 脚本所在目录 = 仓库根目录（无论从哪里调用都能定位项目）
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrontendDir = Join-Path $ProjectRoot "ai-literature-frontend"
$Mvnw = Join-Path $ProjectRoot "mvnw.cmd"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Message" -ForegroundColor Cyan
}

function Assert-Path([string]$Path, [string]$Hint) {
    if (-not (Test-Path $Path)) {
        throw "找不到 $Path。$Hint"
    }
}

function Test-PortListening([int]$Port) {
    $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return [bool]$conns
}

function Start-Backend {
    Assert-Path $Mvnw "请确认仓库根目录存在 mvnw.cmd。"

    if (Test-PortListening 8081) {
        Write-Host "端口 8081 已被占用，认为后端已在运行：http://localhost:8081/api" -ForegroundColor Yellow
        return
    }

    Write-Step "启动后端  Spring Boot  →  http://localhost:8081/api"

    # 跳过测试，加快启动。Flyway 校验失败时可用环境变量放行（历史库常见）：
    #   $env:SPRING_FLYWAY_VALIDATE_ON_MIGRATE = "false"
    $backendCmd = @"
Set-Location '$ProjectRoot'
Write-Host '后端启动中，首次 Maven 下载依赖可能较久...' -ForegroundColor Cyan
`$env:SPRING_FLYWAY_VALIDATE_ON_MIGRATE = if (`$env:SPRING_FLYWAY_VALIDATE_ON_MIGRATE) { `$env:SPRING_FLYWAY_VALIDATE_ON_MIGRATE } else { 'true' }
& '$Mvnw' -f '$ProjectRoot\pom.xml' -DskipTests spring-boot:run
"@

    if ($Here) {
        # 当前窗口阻塞运行，Ctrl+C 停止后端
        Set-Location $ProjectRoot
        & $Mvnw -f "$ProjectRoot\pom.xml" -DskipTests spring-boot:run
        return
    }

    Start-Process -FilePath "powershell.exe" -ArgumentList @(
        "-NoExit",
        "-NoLogo",
        "-Command",
        $backendCmd
    )
    Write-Host "已在新窗口启动后端。就绪标志：日志出现 Started Demo01Application" -ForegroundColor Green
}

function Start-Frontend {
    Assert-Path $FrontendDir "请确认 ai-literature-frontend 目录存在。"

    if (Test-PortListening 5173) {
        Write-Host "端口 5173 已被占用，认为前端已在运行：http://localhost:5173" -ForegroundColor Yellow
        return
    }

    Write-Step "启动前端  Vite        →  http://localhost:5173"

    # 依赖未安装时先 npm install；vite 把 /api 代理到 localhost:8081
    $frontendCmd = @"
Set-Location '$FrontendDir'
if (-not (Test-Path '.\node_modules')) {
    Write-Host '未找到 node_modules，正在 npm install...' -ForegroundColor Yellow
    npm install
}
Write-Host '前端启动中...' -ForegroundColor Cyan
npm run dev
"@

    Start-Process -FilePath "powershell.exe" -ArgumentList @(
        "-NoExit",
        "-NoLogo",
        "-Command",
        $frontendCmd
    )
    Write-Host "已在新窗口启动前端。就绪标志：日志出现 Local: http://localhost:5173/" -ForegroundColor Green
}

Write-Host "项目目录: $ProjectRoot"

if ($BackendOnly -and $FrontendOnly) {
    throw "不要同时指定 -BackendOnly 和 -FrontendOnly。"
}

if ($FrontendOnly) {
    Start-Frontend
} elseif ($BackendOnly) {
    Start-Backend
} else {
    Start-Backend
    if (-not $Here) {
        Start-Frontend
        Write-Host ""
        Write-Host "两个窗口已打开。浏览器访问: http://localhost:5173" -ForegroundColor Green
    }
}
