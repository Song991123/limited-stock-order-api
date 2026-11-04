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
        $process.WaitForExit(10000) | Out-Null
        Write-Output 'Stopped this project server'
    }
}
if ($StopDatabase) { & $NodeExecutable (Join-Path $PSScriptRoot 'local-db.cjs') stop }
