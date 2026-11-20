param([switch]$StopDatabase, [string]$NodeExecutable = (Get-Command node.exe).Source)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$record = Join-Path $project '.runtime/demo-server.json'
if (Test-Path -LiteralPath $record) {
    $server = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
    if ($server.project -ne $project) { throw 'Unexpected server workspace' }
    $process = Get-Process -Id $server.pid -ErrorAction SilentlyContinue
    if ($process) {
        if ($process.StartTime.ToUniversalTime().Ticks -ne $server.startedAtTicks -or $process.Path -ne $server.java) {
            throw 'The recorded PID belongs to a different process; it was not stopped'
        }
        Stop-Process -Id $process.Id
        if (-not $process.WaitForExit(10000)) { throw 'The demo server did not exit within 10 seconds' }
        Write-Output 'Stopped this project server'
    }
}
if ($StopDatabase) {
    $previousInstance = $env:STOCK_DB_TEST_INSTANCE
    try {
        if ($server) { $env:STOCK_DB_TEST_INSTANCE = $server.databaseInstance }
        & $NodeExecutable (Join-Path $PSScriptRoot 'local-db.cjs') stop
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop this project database' }
    } finally { $env:STOCK_DB_TEST_INSTANCE = $previousInstance }
}
