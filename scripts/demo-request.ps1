# 주문과 재요청, 두 번의 취소를 보내고 HTTP 응답과 재고 변화를 단계별로 기록.
param([string]$BaseUrl, [switch]$JsonOnly)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $project '.runtime'
$server = Get-Content -LiteralPath (Join-Path $runtime 'demo-server.json') -Raw | ConvertFrom-Json
if ($server.project -ne $project) { throw 'Unexpected demo server workspace' }
if (-not $BaseUrl) { $BaseUrl = $server.url }
$uri = [Uri]$BaseUrl
if ($uri.Scheme -ne 'http' -or -not $uri.IsLoopback -or $uri.UserInfo -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment) {
    throw 'Use a local demo server URL such as http://127.0.0.1:18086'
}
$BaseUrl = $uri.GetLeftPart([UriPartial]::Authority)
if ($BaseUrl -ne $server.url) { throw 'Use the URL recorded by this project demo server' }
$previousInstance = $env:STOCK_DB_TEST_INSTANCE
try {
    $env:STOCK_DB_TEST_INSTANCE = $server.databaseInstance
    $configuration = (& node.exe (Join-Path $PSScriptRoot 'local-db.cjs') env demo) | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Could not read this project database configuration' }
} finally { $env:STOCK_DB_TEST_INSTANCE = $previousInstance }
$authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("alice:$($configuration.STOCK_ALICE_PASSWORD)"))
$null = Get-Command Invoke-WebRequest
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = $null
$startedAt = [DateTimeOffset]::Now
$runId = $startedAt.ToString('yyyyMMdd-HHmmss-fff')
$reportDirectory = Join-Path $project "build/reports/demo/$runId"
$trace = [ordered]@{
    schemaVersion = 1; startedAt = $startedAt.ToString('o'); finishedAt = $null
    baseUrl = $BaseUrl; passed = $false; failure = $null
    scope = '실제 HTTP 요청과 응답 비교. 직접 DB 조회와 동시 요청 검사는 전체 검사 보고서에서 별도 확인.'
    steps = [Collections.Generic.List[object]]::new()
    inventory = [Collections.Generic.List[object]]::new()
    checks = [Collections.Generic.List[object]]::new()
}
$jar = Join-Path $project 'build/libs/limited-stock-order-api-0.1.0.jar'
if (Test-Path -LiteralPath $jar) { $trace.jarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() }

# 전송 전에 구매 키와 본문 보관. 완료하지 못한 요청도 같은 키로 확인할 수 있게 유지.
function Save-DemoTrace {
    New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
    $temporaryPath = Join-Path $reportDirectory 'trace.next.json'
    $trace | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporaryPath -Encoding UTF8
    Move-Item -LiteralPath $temporaryPath -Destination (Join-Path $reportDirectory 'trace.json') -Force
}

# 인증과 세션은 실제 요청에만 사용. 기록에는 구매 키 외의 헤더를 넣지 않음.
function Invoke-DemoRequest {
    param([string]$Title, [string]$Method, [string]$Path, [string]$Body, [string]$PurchaseKey, [switch]$HideToken)
    $headers = @{ Authorization = $authorization }
    $visibleHeaders = [ordered]@{}
    if ($Method -eq 'POST') { $headers[$csrf.headerName] = $csrf.token }
    if ($PurchaseKey) { $headers['Idempotency-Key'] = $PurchaseKey; $visibleHeaders['Idempotency-Key'] = $PurchaseKey }
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; WebSession = $session; TimeoutSec = 15; MaximumRedirection = 0; SkipHttpErrorCheck = $true }
    if ($Body) { $parameters.Body = $Body; $parameters.ContentType = 'application/json'; $visibleHeaders['Content-Type'] = 'application/json' }
    $step = [ordered]@{
        number = $trace.steps.Count + 1; title = $Title
        request = [ordered]@{ method = $Method; path = $Path; headers = $visibleHeaders; body = $Body }
        response = [ordered]@{ status = $null; headers = [ordered]@{}; body = '응답을 받지 못했습니다. 이 요청의 확정 여부는 아직 알 수 없습니다.'; tokenHidden = [bool]$HideToken }
    }
    $trace.steps.Add($step)
    Save-DemoTrace
    $response = Invoke-WebRequest @parameters
    $step.response.status = [int]$response.StatusCode
    foreach ($name in @('Content-Type', 'Location', 'Retry-After', 'X-Error-Id')) {
        if ($response.Headers[$name]) { $step.response.headers[$name] = [string]::Join(', ', $response.Headers[$name]) }
    }
    $visibleBody = [string]$response.Content
    if ($HideToken) {
        try {
            $masked = $visibleBody | ConvertFrom-Json
            if ($masked.PSObject.Properties.Name -contains 'token') { $masked.token = '[비공개]' }
            $visibleBody = $masked | ConvertTo-Json -Compress
        } catch { $visibleBody = '[발급 응답 본문 비공개: JSON 형식 아님]' }
    }
    $step.response.body = $visibleBody
    Save-DemoTrace
    if (-not $JsonOnly) {
        Write-Host ("`n[{0}] {1}" -f $trace.steps.Count, $Title)
        Write-Host ("  요청: {0} {1}" -f $Method, $Path)
        if ($PurchaseKey) { Write-Host "  Idempotency-Key: $PurchaseKey" }
        if ($Body) { Write-Host "  본문: $Body" }
        Write-Host ("  응답: HTTP {0} {1}" -f $response.StatusCode, $visibleBody)
    }
    return $response
}

# 기대값과 실제 값을 함께 보관. 실패한 조건은 성공으로 넘기지 않고 시연 중단.
function Assert-DemoResult {
    param([string]$Name, $Expected, $Actual)
    $passed = $Expected -eq $Actual
    $trace.checks.Add([ordered]@{ name = $Name; expected = $Expected; actual = $Actual; passed = [bool]$passed })
    if (-not $passed) { throw "시연 확인 실패: $Name" }
}

# 각 쓰기 요청 뒤 상품을 다시 조회해 응답 시점의 재고 기록.
function Read-DemoInventory {
    param([string]$Title, [long]$ProductId, [long]$Expected)
    $response = Invoke-DemoRequest -Title $Title -Method GET -Path "/products/$ProductId"
    Assert-DemoResult "$Title HTTP" 200 ([int]$response.StatusCode)
    $product = $response.Content | ConvertFrom-Json
    $trace.inventory.Add([ordered]@{ stage = $Title; quantity = $product.availableQuantity })
    Assert-DemoResult $Title $Expected $product.availableQuantity
    return $product
}

$summary = $null
$failure = $null
try {
    $csrfResponse = Invoke-DemoRequest -Title '변경 요청에 사용할 세션과 CSRF 토큰 발급' -Method GET -Path '/csrf' -HideToken
    Assert-DemoResult 'CSRF 발급 HTTP' 200 ([int]$csrfResponse.StatusCode)
    $csrf = $csrfResponse.Content | ConvertFrom-Json
    if (-not $csrf.headerName -or -not $csrf.token) { throw 'CSRF response is missing its header name or token' }
    $productsResponse = Invoke-DemoRequest -Title '상품과 주문 전 재고 조회' -Method GET -Path '/products'
    Assert-DemoResult '상품 목록 HTTP' 200 ([int]$productsResponse.StatusCode)
    $product = ($productsResponse.Content | ConvertFrom-Json) | Where-Object availableQuantity -gt 0 | Select-Object -First 1
    if (-not $product) { throw 'No product with available inventory' }
    $trace.productId = $product.id
    $product = Read-DemoInventory '주문 전' $product.id $product.availableQuantity
    $key = [Guid]::NewGuid().ToString('N')
    $body = @{ productId = $product.id; quantity = 1 } | ConvertTo-Json -Compress

    $first = Invoke-DemoRequest -Title '새 구매 키로 상품 1개 주문' -Method POST -Path '/orders' -Body $body -PurchaseKey $key
    Assert-DemoResult '새 주문 HTTP' 201 ([int]$first.StatusCode)
    $order = $first.Content | ConvertFrom-Json
    Assert-DemoResult '접수된 주문 상태' 'ACCEPTED' $order.status
    Assert-DemoResult '접수된 상품' $product.id $order.productId
    Assert-DemoResult '접수된 주문 수량' 1 $order.quantity
    $null = Read-DemoInventory '주문 후' $product.id ($product.availableQuantity - 1)

    $replay = Invoke-DemoRequest -Title '같은 구매 키와 본문으로 재요청' -Method POST -Path '/orders' -Body $body -PurchaseKey $key
    Assert-DemoResult '같은 구매 재요청 HTTP' 200 ([int]$replay.StatusCode)
    $replayed = $replay.Content | ConvertFrom-Json
    Assert-DemoResult '재요청의 주문 ID 유지' $order.id $replayed.id
    Assert-DemoResult '재요청의 주문 상태' 'ACCEPTED' $replayed.status
    $null = Read-DemoInventory '재요청 후 추가 차감 없음' $product.id ($product.availableQuantity - 1)

    $cancelResponse = Invoke-DemoRequest -Title '접수된 주문 취소' -Method POST -Path "/orders/$($order.id)/cancel"
    Assert-DemoResult '처음 취소 HTTP' 200 ([int]$cancelResponse.StatusCode)
    $cancel = $cancelResponse.Content | ConvertFrom-Json
    Assert-DemoResult '처음 취소의 주문 ID' $order.id $cancel.id
    Assert-DemoResult '처음 취소 상태' 'CANCELLED' $cancel.status
    $null = Read-DemoInventory '처음 취소 후 재고 복구' $product.id $product.availableQuantity

    $againResponse = Invoke-DemoRequest -Title '이미 취소한 주문 다시 취소' -Method POST -Path "/orders/$($order.id)/cancel"
    Assert-DemoResult '재취소 HTTP' 200 ([int]$againResponse.StatusCode)
    $again = $againResponse.Content | ConvertFrom-Json
    Assert-DemoResult '재취소의 주문 ID 유지' $order.id $again.id
    Assert-DemoResult '재취소 상태' 'CANCELLED' $again.status
    $after = Read-DemoInventory '재취소 후 추가 복구 없음' $product.id $product.availableQuantity
    $summary = [ordered]@{
        firstHttp = [int]$first.StatusCode; replayHttp = [int]$replay.StatusCode
        sameOrder = ($order.id -eq $replayed.id); orderId = $order.id
        cancelStatus = $cancel.status; repeatedCancelStatus = $again.status
        stockBefore = $product.availableQuantity; stockAfter = $after.availableQuantity
    }
    $trace.summary = $summary
    $trace.passed = $true
} catch {
    $failure = $_
    # HTTP 오류 본문이나 인증 설정이 예외 메시지를 통해 결과에 복사되지 않도록 제한.
    $trace.failure = if ($_.Exception.Message -like '시연 확인 실패:*') { $_.Exception.Message } else { '시연을 완료하지 못했습니다. 서버 로그와 마지막 요청의 응답을 확인해주세요.' }
} finally {
    $trace.finishedAt = [DateTimeOffset]::Now.ToString('o')
    Save-DemoTrace
    $tracePath = Join-Path $reportDirectory 'trace.json'
    & node.exe (Join-Path $PSScriptRoot 'demo/report.cjs') $tracePath (Join-Path $reportDirectory 'index.html') (Join-Path $project 'build/reports/demo/index.html')
    if ($LASTEXITCODE -ne 0) { throw 'Could not generate the demo report; the trace JSON was preserved' }
    if (-not $JsonOnly) { Write-Host "`n단계별 결과: $(Join-Path $project 'build/reports/demo/index.html')" }
}
if ($failure) { throw $trace.failure }
$summary['checksPassed'] = $trace.checks.Count
$summary['report'] = 'build/reports/demo/index.html'
$summary | ConvertTo-Json
