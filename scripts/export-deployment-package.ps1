param(
  [string]$OutputDir = "",
  [string]$PostgresContainer = "",
  [string]$PostgresDatabase = "",
  [string]$PostgresUser = "",
  [string]$Neo4jContainer = "",
  [switch]$SkipNeo4j,
  [switch]$SkipHostData
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
  throw $Message
}

function Require-Command([string]$Name) {
  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    Fail "Required command not found: $Name"
  }
}

function Get-DotEnvValue([string]$Path, [string]$Name, [string]$DefaultValue) {
  if (-not (Test-Path $Path)) {
    return $DefaultValue
  }
  $line = Get-Content -Encoding UTF8 $Path |
    Where-Object { $_ -match "^\s*$([regex]::Escape($Name))=" } |
    Select-Object -First 1
  if (-not $line) {
    return $DefaultValue
  }
  return ($line -split "=", 2)[1].Trim().Trim('"').Trim("'")
}

function Invoke-Tool {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [string[]]$Arguments = @()
  )
  & $FilePath @Arguments
  if ($LASTEXITCODE -ne 0) {
    Fail "Command failed: $FilePath $($Arguments -join ' ')"
  }
}

function Get-RunningContainerRows {
  $rows = & docker ps --format "{{.Names}}`t{{.Image}}"
  if ($LASTEXITCODE -ne 0) {
    Fail "Failed to list Docker containers"
  }
  return $rows
}

function Test-PostgresDatabase {
  param(
    [string]$Container,
    [string]$User,
    [string]$Database
  )
  & docker exec $Container psql -U $User -d $Database -At -c "select 1" *> $null
  return ($LASTEXITCODE -eq 0)
}

function Find-PostgresContainer {
  param(
    [string]$User,
    [string]$Database
  )
  $candidates = Get-RunningContainerRows |
    ForEach-Object {
      $parts = $_ -split "`t", 2
      [pscustomobject]@{ Name = $parts[0]; Image = if ($parts.Length -gt 1) { $parts[1] } else { "" } }
    } |
    Where-Object { $_.Image -match "postgres|pgvector" -or $_.Name -match "postgres" } |
    Sort-Object @{ Expression = { if ($_.Name -eq "ai-code-postgres") { 0 } elseif ($_.Name -match "demo|literature") { 1 } else { 2 } } }, Name

  foreach ($candidate in $candidates) {
    if (Test-PostgresDatabase -Container $candidate.Name -User $User -Database $Database) {
      return $candidate.Name
    }
  }
  return ""
}

function Find-Neo4jContainer {
  $candidates = Get-RunningContainerRows |
    ForEach-Object {
      $parts = $_ -split "`t", 2
      [pscustomobject]@{ Name = $parts[0]; Image = if ($parts.Length -gt 1) { $parts[1] } else { "" } }
    } |
    Where-Object { $_.Image -match "neo4j" -or $_.Name -match "neo4j" } |
    Sort-Object @{ Expression = { if ($_.Name -match "ai_literature") { 0 } elseif ($_.Name -match "demo") { 1 } else { 2 } } }, Name

  $first = $candidates | Select-Object -First 1
  if ($first) {
    return $first.Name
  }
  return ""
}

function Copy-SourceTree {
  param(
    [string]$Root,
    [string]$Destination
  )
  $excludeDirs = @(
    ".git",
    ".gitnexus",
    ".idea",
    ".vscode",
    ".m2",
    ".mvn-home",
    ".npm-cache",
    ".omx",
    ".playwright-mcp",
    ".codex",
    "node_modules",
    "target",
    "data",
    "Evidence",
    "outputs",
    "output",
    "tmp",
    "logs",
    "presentation-output",
    "PreTreatment\outputs",
    "scripts\node_modules",
    "scripts\__pycache__",
    "scripts\ontology_demo\__pycache__",
    "src\main\resources\docs_1",
    "ai-literature-frontend\node_modules",
    "ai-literature-frontend\dist"
  ) | ForEach-Object { Join-Path $Root $_ }

  $excludeFiles = @(
    ".env",
    ".env.local",
    "*.local",
    "*.log",
    "*-deploy-*.zip",
    "*-deploy-*.tar.gz",
    "ai-literature-images.tar"
  )

  $args = @($Root, $Destination, "/MIR", "/NFL", "/NDL", "/NJH", "/NJS", "/NP", "/XD") + $excludeDirs + @("/XF") + $excludeFiles
  & robocopy @args | Out-Null
  if ($LASTEXITCODE -gt 7) {
    Fail "robocopy failed with exit code $LASTEXITCODE"
  }
  $global:LASTEXITCODE = 0
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
if (-not $OutputDir) {
  $OutputDir = Join-Path $projectRoot "outputs\deployment"
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)

Require-Command docker
Require-Command tar
Require-Command robocopy

$envPath = Join-Path $projectRoot ".env"
if (-not $PostgresDatabase) {
  $PostgresDatabase = Get-DotEnvValue -Path $envPath -Name "POSTGRES_DB" -DefaultValue "demo_01"
}
if (-not $PostgresUser) {
  $PostgresUser = Get-DotEnvValue -Path $envPath -Name "POSTGRES_USER" -DefaultValue "demo_01"
}

if (-not $PostgresContainer) {
  $PostgresContainer = Find-PostgresContainer -User $PostgresUser -Database $PostgresDatabase
}
if (-not $PostgresContainer) {
  Fail "No running PostgreSQL container can access database '$PostgresDatabase' as user '$PostgresUser'. Start the source DB or pass -PostgresContainer."
}

if (-not (Test-PostgresDatabase -Container $PostgresContainer -User $PostgresUser -Database $PostgresDatabase)) {
  Fail "PostgreSQL container '$PostgresContainer' cannot access database '$PostgresDatabase' as user '$PostgresUser'."
}

if (-not $SkipNeo4j -and -not $Neo4jContainer) {
  $Neo4jContainer = Find-Neo4jContainer
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stageRoot = Join-Path $OutputDir "stage-$timestamp"
$sourceDir = Join-Path $stageRoot "source"
$backupDir = Join-Path $stageRoot "backup"
New-Item -ItemType Directory -Force -Path $sourceDir, $backupDir | Out-Null

Write-Host "Copying source tree..."
Copy-SourceTree -Root $projectRoot -Destination $sourceDir

Write-Host "Exporting PostgreSQL from container '$PostgresContainer', database '$PostgresDatabase'..."
$pgDumpName = "postgres-$PostgresDatabase.dump"
$containerDumpPath = "/tmp/$pgDumpName"
$hostDumpPath = Join-Path $backupDir $pgDumpName
& docker exec $PostgresContainer rm -f $containerDumpPath | Out-Null
Invoke-Tool -FilePath docker -Arguments @("exec", $PostgresContainer, "pg_dump", "-U", $PostgresUser, "-d", $PostgresDatabase, "-Fc", "-Z", "9", "-f", $containerDumpPath)
Invoke-Tool -FilePath docker -Arguments @("cp", "${PostgresContainer}:$containerDumpPath", $hostDumpPath)
& docker exec $PostgresContainer rm -f $containerDumpPath | Out-Null

if (-not $SkipHostData) {
  $dataPath = Join-Path $projectRoot "data"
  $evidencePath = Join-Path $projectRoot "Evidence"
  if ((Test-Path $dataPath) -or (Test-Path $evidencePath)) {
    Write-Host "Archiving host data directories..."
    $hostDataArchive = Join-Path $backupDir "host-data-evidence.tar.gz"
    $items = @()
    if (Test-Path $dataPath) { $items += "data" }
    if (Test-Path $evidencePath) { $items += "Evidence" }
    Invoke-Tool -FilePath tar -Arguments (@("-czf", $hostDataArchive, "-C", $projectRoot) + $items)
  } else {
    Write-Warning "No data/ or Evidence/ directory found; skipping host data archive."
  }
}

$neo4jVolumeName = ""
$neo4jWasRunning = $false
if (-not $SkipNeo4j) {
  if ($Neo4jContainer) {
    Write-Host "Exporting Neo4j volume from container '$Neo4jContainer'..."
    $inspect = (& docker inspect $Neo4jContainer | ConvertFrom-Json)
    if ($LASTEXITCODE -ne 0 -or -not $inspect) {
      Fail "Failed to inspect Neo4j container '$Neo4jContainer'."
    }
    $mount = $inspect[0].Mounts | Where-Object { $_.Destination -eq "/data" } | Select-Object -First 1
    if (-not $mount -or -not $mount.Name) {
      Fail "Neo4j container '$Neo4jContainer' does not expose a named /data volume."
    }
    $neo4jVolumeName = $mount.Name
    $neo4jWasRunning = [bool]$inspect[0].State.Running

    try {
      if ($neo4jWasRunning) {
        Write-Host "Stopping Neo4j briefly for a consistent volume archive..."
        Invoke-Tool -FilePath docker -Arguments @("stop", $Neo4jContainer)
      }
      Invoke-Tool -FilePath docker -Arguments @("run", "--rm", "-v", "${neo4jVolumeName}:/data:ro", "-v", "${backupDir}:/backup", "alpine:3.20", "tar", "czf", "/backup/neo4j-data.tar.gz", "-C", "/data", ".")
    } finally {
      if ($neo4jWasRunning) {
        Write-Host "Restarting Neo4j..."
        & docker start $Neo4jContainer | Out-Null
        if ($LASTEXITCODE -ne 0) {
          Write-Warning "Failed to restart Neo4j container '$Neo4jContainer'. Start it manually with Docker."
        }
      }
    }
  } else {
    Write-Warning "No running Neo4j container found; skipping Neo4j archive."
  }
}

$postgresVersion = (& docker exec $PostgresContainer pg_dump --version) -join "`n"
$postgresServerVersion = (& docker exec $PostgresContainer psql -U $PostgresUser -d $PostgresDatabase -At -c "select version();") -join "`n"
$gitCommit = (& git -C $projectRoot rev-parse HEAD 2>$null) -join "`n"
$gitBranch = (& git -C $projectRoot rev-parse --abbrev-ref HEAD 2>$null) -join "`n"

$manifest = [ordered]@{
  created_at = (Get-Date).ToString("s")
  source_project_root = "$projectRoot"
  git_branch = $gitBranch.Trim()
  git_commit = $gitCommit.Trim()
  postgres = [ordered]@{
    container = $PostgresContainer
    database = $PostgresDatabase
    user = $PostgresUser
    dump_file = "backup/$pgDumpName"
    pg_dump_version = $postgresVersion.Trim()
    server_version = $postgresServerVersion.Trim()
  }
  neo4j = [ordered]@{
    container = $Neo4jContainer
    source_volume = $neo4jVolumeName
    archive_file = if ($neo4jVolumeName) { "backup/neo4j-data.tar.gz" } else { "" }
  }
  host_data_archive = if (Test-Path (Join-Path $backupDir "host-data-evidence.tar.gz")) { "backup/host-data-evidence.tar.gz" } else { "" }
  notes = @(
    "The real .env file is intentionally excluded.",
    "Use docker-compose.pg17.override.yml when restoring this package if the PostgreSQL dump was created from PostgreSQL 17."
  )
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $stageRoot "restore-manifest.json")

$packagePath = Join-Path $OutputDir "ai-literature-deploy-$timestamp.tar.gz"
Write-Host "Creating package $packagePath..."
Invoke-Tool -FilePath tar -Arguments @("-czf", $packagePath, "-C", $stageRoot, ".")

Write-Host ""
Write-Host "Package created:"
Write-Host $packagePath
Write-Host ""
Write-Host "Manifest:"
Write-Host (Join-Path $stageRoot "restore-manifest.json")
