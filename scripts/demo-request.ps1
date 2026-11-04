param([string]$BaseUrl)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$runtime = Join-Path $project '.runtime'
if (-not $BaseUrl) { $BaseUrl = (Get-Content -LiteralPath (Join-Path $runtime 'demo-server.json') -Raw | ConvertFrom-Json).url }
$configuration = (& node.exe (Join-Path $PSScriptRoot 'local-db.cjs') env demo) | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Could not read this project database configuration' }
$authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("alice:$($configuration.STOCK_ALICE_PASSWORD)"))
$headers = @{ Authorization = $authorization }
$csrf = Invoke-RestMethod -Uri "$BaseUrl/csrf" -Headers $headers -SessionVariable session
$headers[$csrf.headerName] = $csrf.token
$products = Invoke-RestMethod -Uri "$BaseUrl/products" -Headers $headers -WebSession $session
$product = $products | Where-Object availableQuantity -gt 0 | Select-Object -First 1
if (-not $product) { throw 'No product with available inventory' }
$key = [Guid]::NewGuid().ToString('N')
$headers['Idempotency-Key'] = $key
$body = @{ productId = $product.id; quantity = 1 } | ConvertTo-Json -Compress
$first = Invoke-WebRequest -Method Post -Uri "$BaseUrl/orders" -Headers $headers -WebSession $session -ContentType application/json -Body $body
$order = $first.Content | ConvertFrom-Json
$replay = Invoke-WebRequest -Method Post -Uri "$BaseUrl/orders" -Headers $headers -WebSession $session -ContentType application/json -Body $body
$cancel = Invoke-RestMethod -Method Post -Uri "$BaseUrl/orders/$($order.id)/cancel" -Headers $headers -WebSession $session
$again = Invoke-RestMethod -Method Post -Uri "$BaseUrl/orders/$($order.id)/cancel" -Headers $headers -WebSession $session
$after = Invoke-RestMethod -Uri "$BaseUrl/products/$($product.id)" -Headers $headers -WebSession $session
[ordered]@{ firstHttp = [int]$first.StatusCode; replayHttp = [int]$replay.StatusCode; sameOrder = ($order.id -eq ($replay.Content | ConvertFrom-Json).id); orderId = $order.id; cancelStatus = $cancel.status; repeatedCancelStatus = $again.status; stockBefore = $product.availableQuantity; stockAfter = $after.availableQuantity } | ConvertTo-Json
