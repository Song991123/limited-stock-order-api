param([string]$NodeExecutable = (Get-Command node.exe).Source, [switch]$TestsOnly, [switch]$IsolatedDatabase)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = Join-Path $project "evidence/raw/$runId"
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$previousInstance = $env:STOCK_DB_TEST_INSTANCE
if ($IsolatedDatabase) { $env:STOCK_DB_TEST_INSTANCE = 'isolated-' + [Guid]::NewGuid().ToString('N').Substring(0,16) }
$wasRunning = ((& $NodeExecutable (Join-Path $PSScriptRoot 'local-db.cjs') status) | ConvertFrom-Json).running
Push-Location $project
try {
    & $NodeExecutable (Join-Path $PSScriptRoot 'local-db.cjs') init
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated database initialization failed' }
    if ($IsolatedDatabase) {
        $instanceManifest = Join-Path $project ".runtime/$($env:STOCK_DB_TEST_INSTANCE)/database.json"
        $instanceMeta = Get-Content -LiteralPath $instanceManifest -Raw | ConvertFrom-Json
        [ordered]@{ instance=$env:STOCK_DB_TEST_INSTANCE; data=$instanceMeta.data; port=$instanceMeta.port; pid=[int](Get-Content -LiteralPath (Join-Path $instanceMeta.data 'postmaster.pid') -TotalCount 1) } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'isolated-base-cluster.json') -Encoding UTF8
    }
    $configuration = (& $NodeExecutable (Join-Path $PSScriptRoot 'local-db.cjs') env test) | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the dedicated database configuration' }
    foreach ($property in $configuration.PSObject.Properties) {
        [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
    }
    $env:STOCK_EVIDENCE_DIR = $evidence
    @(& (Join-Path $PSScriptRoot 'source-snapshot.ps1')) | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'source-sha256.json') -Encoding UTF8
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-Command java.exe).Source)
    $workspaceBytes = [System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($project))
    $workspaceKey = [Convert]::ToHexString($workspaceBytes).ToLowerInvariant().Substring(0, 12)
    $env:GRADLE_USER_HOME = Join-Path ([IO.Path]::GetTempPath()) "stock-order-mini-$workspaceKey/gradle-home"
    $env:STOCK_BUILD_DIR = Join-Path ([IO.Path]::GetTempPath()) "stock-order-mini-$workspaceKey/build"
    $tasks = @('clean', 'test')
    if (-not $TestsOnly) { $tasks += 'bootJar' }
    & (Join-Path $project 'gradlew.bat') --no-daemon @tasks 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'gradle.log')
    $exitCode = $LASTEXITCODE
    if (Test-Path -LiteralPath (Join-Path $env:STOCK_BUILD_DIR 'test-results/test')) {
        Copy-Item -LiteralPath (Join-Path $env:STOCK_BUILD_DIR 'test-results/test') -Destination (Join-Path $evidence 'junit-xml') -Recurse
    }
    if (Test-Path -LiteralPath (Join-Path $env:STOCK_BUILD_DIR 'reports/tests/test')) {
        Copy-Item -LiteralPath (Join-Path $env:STOCK_BUILD_DIR 'reports/tests/test') -Destination (Join-Path $evidence 'junit-html') -Recurse
    }
    if (Test-Path -LiteralPath $env:STOCK_BUILD_DIR) {
        New-Item -ItemType Directory -Path (Join-Path $project 'build') -Force | Out-Null
        Get-ChildItem -LiteralPath $env:STOCK_BUILD_DIR | Where-Object { -not $TestsOnly -or $_.Name -ne 'libs' } | Copy-Item -Destination (Join-Path $project 'build') -Recurse -Force
    }
    $summary = [ordered]@{ buildExit = $exitCode; tests = 0; failures = 0; errors = 0; skipped = 0; testsOnly = [bool]$TestsOnly; isolatedDatabase = [bool]$IsolatedDatabase; evidenceDirectory = $evidence }
    if (Test-Path -LiteralPath (Join-Path $evidence 'junit-xml')) {
        foreach ($file in Get-ChildItem -LiteralPath (Join-Path $evidence 'junit-xml') -Filter 'TEST-*.xml') {
            $suite = ([xml](Get-Content -LiteralPath $file.FullName -Raw)).testsuite
            foreach ($key in @('tests', 'failures', 'errors', 'skipped')) { $summary[$key] += [int]$suite.$key }
        }
    }
    $summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding UTF8
    if ($exitCode -ne 0) { throw "Build or tests failed; see $evidence" }
    if ($summary.tests -eq 0) { throw 'No tests ran' }
    $summary | ConvertTo-Json
} finally {
    if (-not $wasRunning) { & $NodeExecutable (Join-Path $PSScriptRoot 'local-db.cjs') stop }
    if ($IsolatedDatabase -and $instanceMeta) {
        [ordered]@{ instance=$env:STOCK_DB_TEST_INSTANCE; port=$instanceMeta.port; pidFileExists=(Test-Path -LiteralPath (Join-Path $instanceMeta.data 'postmaster.pid')) } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'isolated-base-cleanup.json') -Encoding UTF8
    }
    $env:STOCK_DB_TEST_INSTANCE = $previousInstance
    Pop-Location
}
