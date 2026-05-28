param(
    [string]$ComposeFile = "docker-compose.prod.yml",
    [string]$ProjectName = "",
    [string]$Output = "ai-literature-images.tar",
    [switch]$BusinessOnly,
    [switch]$NoPull
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..")
Set-Location $ProjectRoot

function Invoke-Docker {
    param([string[]]$Arguments)

    docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Read-DotEnvValue {
    param(
        [string]$Key,
    [string]$DefaultValue = ""
)

    $envValue = [Environment]::GetEnvironmentVariable($Key)
    if ($envValue) {
        return $envValue
    }

    $envFile = Join-Path $ProjectRoot ".env"
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match "^$([regex]::Escape($Key))=" } | Select-Object -Last 1
        if ($line) {
            return ($line -replace "^[^=]*=", "").Trim()
        }
    }

    return $DefaultValue
}

if (-not $ProjectName) {
    $ProjectName = if ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME } else { Read-DotEnvValue "COMPOSE_PROJECT_NAME" "ai_literature" }
}

$compose = @("compose", "-p", $ProjectName, "-f", $ComposeFile)

if (-not $NoPull -and -not $BusinessOnly) {
    Invoke-Docker ($compose + @("pull", "postgres", "grobid", "neo4j"))
}

$buildArgs = @("build")
if (-not $NoPull) {
    $buildArgs += "--pull"
}
$buildArgs += @("backend", "web")
Invoke-Docker ($compose + $buildArgs)

$images = docker @($compose + @("config", "--images"))
if ($LASTEXITCODE -ne 0) {
    throw "docker $($compose + @("config", "--images") -join ' ') failed with exit code $LASTEXITCODE"
}
if ($BusinessOnly) {
    $backendImage = Read-DotEnvValue "BACKEND_IMAGE" "ai_literature-backend:latest"
    $webImage = Read-DotEnvValue "WEB_IMAGE" "ai_literature-web:latest"
    $images = @($backendImage, $webImage)
}

$images = $images | Where-Object { $_ } | Select-Object -Unique
if (-not $images -or $images.Count -eq 0) {
    throw "No images resolved from $ComposeFile."
}

docker image inspect $images | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "docker image inspect $($images -join ' ') failed with exit code $LASTEXITCODE"
}
Invoke-Docker (@("save", "-o", $Output) + $images)

Write-Host "Saved images to $Output"
$images | ForEach-Object { Write-Host " - $_" }
