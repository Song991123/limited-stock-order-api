param([int]$Port = 18086, [string]$NodeExecutable = (Get-Command node.exe).Source)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $project '.runtime'
$record = Join-Path $runtime 'demo-server.json'
$jar = Join-Path $project 'build/libs/limited-stock-order-api-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Run scripts/test.ps1 first to build the executable jar' }
if (Test-Path -LiteralPath $record) {
    $old = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
    $existing = Get-Process -Id $old.pid -ErrorAction SilentlyContinue
    if ($existing -and $existing.StartTime.ToUniversalTime().Ticks -eq $old.startedAtTicks) {
        throw "This project's server is already running at $($old.url)"
    }
}
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
try { $listener.Start() } catch { throw "Port $Port is in use; choose another with -Port" } finally { $listener.Stop() }
& $NodeExecutable (Join-Path $PSScriptRoot 'local-db.cjs') init
if ($LASTEXITCODE -ne 0) { throw 'Dedicated database initialization failed' }
$configuration = (& $NodeExecutable (Join-Path $PSScriptRoot 'local-db.cjs') env demo) | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Could not read demo configuration' }
foreach ($property in $configuration.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
}
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:STOCK_PORT = [string]$Port
$java = (Get-Command java.exe).Source
$process = Start-Process -FilePath $java -ArgumentList @('-jar', ('"{0}"' -f $jar)) -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'demo-stdout.log') -RedirectStandardError (Join-Path $runtime 'demo-stderr.log')
$server = [ordered]@{ pid = $process.Id; startedAtTicks = $process.StartTime.ToUniversalTime().Ticks; project = $project; java = $java; url = "http://127.0.0.1:$Port" }
$server | ConvertTo-Json | Set-Content -LiteralPath $record -Encoding UTF8
$deadline = [DateTime]::UtcNow.AddSeconds(60)
$authorization = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("alice:$($configuration.STOCK_ALICE_PASSWORD)"))
do {
    $process.Refresh()
    if ($process.HasExited) { throw 'The server exited; see .runtime/demo-stdout.log and demo-stderr.log' }
    try {
        $null = Invoke-RestMethod -Uri "$($server.url)/products" -Headers @{ Authorization = "Basic $authorization" } -TimeoutSec 2
        Write-Output "Order API is ready: $($server.url)"
        exit 0
    } catch { Start-Sleep -Milliseconds 200 }
} while ([DateTime]::UtcNow -lt $deadline)
throw 'Server startup timed out; inspect the logs and use scripts/stop-demo.ps1'
