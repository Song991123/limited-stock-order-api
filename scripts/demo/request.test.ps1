# 시연 도우미의 기록과 실패 판정 검사. HTTP 요청은 대역으로 바꾸고 서버와 DB는 실행하지 않음.
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$source = Join-Path $project 'scripts/demo-request.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'PowerShell syntax errors in demo-request.ps1' }
$functions = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $true)
foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
$fixture = Join-Path $project ('.runtime/demo-unit-' + [Guid]::NewGuid().ToString('N'))
$reportDirectory = $fixture
$BaseUrl = 'http://127.0.0.1:18086'
$authorization = 'Basic FAKE_AUTH_SECRET'
$null = Get-Command Invoke-WebRequest
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = @{ headerName = 'X-CSRF-TOKEN'; token = 'FAKE_CSRF_SECRET' }
$JsonOnly = $true
$checks = [Collections.Generic.List[string]]::new()
$mode = 'order'
$trace = [ordered]@{ steps = [Collections.Generic.List[object]]::new(); inventory = [Collections.Generic.List[object]]::new(); checks = [Collections.Generic.List[object]]::new() }

function Confirm-Test {
    param([string]$Name, [bool]$Condition)
    if (-not $Condition) { throw "Failed: $Name" }
    $checks.Add($Name)
}

# Invoke-DemoRequest에서 호출할 HTTP 대역. 저장된 요청을 먼저 읽어 전송 전 기록 여부 확인.
function Invoke-WebRequest {
    param($Method, $Uri, $Headers, $WebSession, $TimeoutSec, $MaximumRedirection, [switch]$SkipHttpErrorCheck, $Body, $ContentType)
    $stored = Get-Content -LiteralPath (Join-Path $reportDirectory 'trace.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Confirm-Test 'request persisted before HTTP execution' ($stored.steps[-1].response.status -eq $null)
    Confirm-Test 'redirects disabled and timeout bounded' ($MaximumRedirection -eq 0 -and $TimeoutSec -eq 15)
    if ($mode -eq 'lost') { throw 'simulated response loss' }
    $content = switch ($mode) {
        'csrf' { '{"token":"FAKE_CSRF_SECRET","headerName":"X-CSRF-TOKEN","parameterName":"_csrf"}' }
        'csrf-error' { '{"code":"UNAUTHORIZED"}' }
        'csrf-nonjson' { 'FAKE_CSRF_SECRET' }
        default { '{"id":7,"status":"ACCEPTED"}' }
    }
    $status = switch ($mode) { 'csrf-error' { 401 } 'csrf-nonjson' { 503 } default { 200 } }
    return [pscustomobject]@{ StatusCode = $status; Content = $content; Headers = @{ 'Set-Cookie' = 'FAKE_COOKIE_SECRET'; 'Location' = '/orders/7'; 'Content-Type' = 'application/json' } }
}

try {
    $null = Invoke-DemoRequest -Title '대역 요청' -Method POST -Path '/orders' -Body '{"productId":1,"quantity":1}' -PurchaseKey 'same-purchase-key'
    $storedText = Get-Content -LiteralPath (Join-Path $reportDirectory 'trace.json') -Raw -Encoding UTF8
    Confirm-Test 'purchase key and request body retained' ($storedText.Contains('same-purchase-key') -and $storedText.Contains('productId'))
    Confirm-Test 'authentication and cookie values omitted' (-not $storedText.Contains('FAKE_AUTH_SECRET') -and -not $storedText.Contains('FAKE_COOKIE_SECRET') -and -not $storedText.Contains('FAKE_CSRF_SECRET'))
    Confirm-Test 'safe response Location retained' ($storedText.Contains('/orders/7'))
    $mode = 'csrf'
    $null = Invoke-DemoRequest -Title 'CSRF 대역' -Method GET -Path '/csrf' -HideToken
    $storedText = Get-Content -LiteralPath (Join-Path $reportDirectory 'trace.json') -Raw -Encoding UTF8
    Confirm-Test 'CSRF token omitted but actual header name retained' (-not $storedText.Contains('FAKE_CSRF_SECRET') -and $storedText.Contains('X-CSRF-TOKEN') -and $storedText.Contains('[비공개]'))
    $mode = 'csrf-error'
    $null = Invoke-DemoRequest -Title 'CSRF 오류 응답 대역' -Method GET -Path '/csrf' -HideToken
    Confirm-Test 'CSRF authentication error keeps its actual HTTP status' ($trace.steps[-1].response.status -eq 401 -and $trace.steps[-1].response.body.Contains('UNAUTHORIZED'))
    $mode = 'csrf-nonjson'
    $null = Invoke-DemoRequest -Title 'CSRF 비 JSON 응답 대역' -Method GET -Path '/csrf' -HideToken
    Confirm-Test 'non JSON CSRF error keeps status without exposing its body' ($trace.steps[-1].response.status -eq 503 -and -not $trace.steps[-1].response.body.Contains('FAKE_CSRF_SECRET'))
    $mode = 'lost'
    $lost = $false
    try { $null = Invoke-DemoRequest -Title '응답 유실 대역' -Method POST -Path '/orders' -Body '{"productId":1,"quantity":1}' -PurchaseKey 'original-key' } catch { $lost = $true }
    $stored = Get-Content -LiteralPath (Join-Path $reportDirectory 'trace.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Confirm-Test 'lost response preserves original purchase key and unknown outcome' ($lost -and $stored.steps[-1].request.headers.'Idempotency-Key' -eq 'original-key' -and $null -eq $stored.steps[-1].response.status)
    $rejected = $false
    try { Assert-DemoResult '틀린 재고' 10 9 } catch { $rejected = $true }
    Confirm-Test 'incorrect inventory makes the demo fail' ($rejected -and $trace.checks[-1].passed -eq $false)
    Assert-DemoResult '같은 주문' 7 7
    Confirm-Test 'matching order result is recorded' ($trace.checks[-1].passed -eq $true)
    [ordered]@{ scope = 'mock HTTP and local trace only; no server or DB execution'; passed = $checks.Count; checks = $checks } | ConvertTo-Json -Depth 3
} finally {
    if (Test-Path -LiteralPath $fixture) {
        $resolved = (Resolve-Path -LiteralPath $fixture).Path
        $parent = [IO.Path]::GetFullPath((Join-Path $project '.runtime'))
        if ((Split-Path -Parent $resolved) -ne $parent -or (Split-Path -Leaf $resolved) -notmatch '^demo-unit-[a-f0-9]{32}$') { throw 'Unexpected test fixture path; not removed' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
