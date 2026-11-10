param(
    [Parameter(Mandatory)][string]$SuiteRun,
    [Parameter(Mandatory)][string]$RecoveryRun,
    [Parameter(Mandatory)][string]$QueryRun,
    [Parameter(Mandatory)][string]$BeforeUnitRun,
    [Parameter(Mandatory)][string]$BeforeHttpRun
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$rawRoot = [IO.Path]::GetFullPath((Join-Path $project 'evidence/raw')) + [IO.Path]::DirectorySeparatorChar
function Read-Json($directory, $name) { Get-Content -LiteralPath (Join-Path $directory $name) -Raw | ConvertFrom-Json }
function Resolve-Run($value) {
    $resolved = (Resolve-Path -LiteralPath $value).Path
    if (-not $resolved.StartsWith($rawRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use this project evidence/raw only' }
    return $resolved
}
function Test-Cleanup($directory) {
    if ((Read-Json $directory 'isolated-base-cleanup.json').pidFileExists) { throw 'Owned DB is still running' }
}
function Read-Cases($directory) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $directory 'junit-xml') -Filter 'TEST-*.xml') {
        $suite = ([xml](Get-Content -LiteralPath $file.FullName -Raw)).testsuite
        foreach ($case in $suite.testcase) {
            [pscustomobject]@{ name="$($suite.name)::$($case.name)"; failed=($null -ne $case.failure); message=[string]$case.failure.message }
        }
    }
}
$SuiteRun = Resolve-Run $SuiteRun; $RecoveryRun = Resolve-Run $RecoveryRun; $QueryRun = Resolve-Run $QueryRun
$BeforeUnitRun = Resolve-Run $BeforeUnitRun; $BeforeHttpRun = Resolve-Run $BeforeHttpRun
$current = @(& (Join-Path $PSScriptRoot 'source-snapshot.ps1')) | ConvertTo-Json -Depth 4 | ConvertFrom-Json
foreach ($directory in @($SuiteRun, $RecoveryRun)) {
    $summary = Read-Json $directory 'summary.json'
    if ($summary.buildExit -ne 0 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.skipped -ne 0) { throw 'A required suite failed' }
    if ((Read-Json $directory 'source-sha256.json' | ConvertTo-Json -Compress) -ne ($current | ConvertTo-Json -Compress)) { throw 'Source changed after execution' }
    Test-Cleanup $directory
}
$suiteSummary = Read-Json $SuiteRun 'summary.json'; $recoverySummary = Read-Json $RecoveryRun 'summary.json'
if ($suiteSummary.tests -ne 160 -or $suiteSummary.testsOnly -or $suiteSummary.databaseRecovery -or $recoverySummary.tests -ne 15 -or -not $recoverySummary.databaseRecovery) { throw 'Unexpected suite scope' }
$cases = @(Read-Cases $SuiteRun)
# 이전 154건의 이름을 실제 main 기록에서 읽어 누락과 중복 확인. XML의 BOM도 보존해서 읽음.
$baselineText = (& git -C $project show '5e5fcec:evidence/verification/junit-summary.xml') -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Baseline JUnit evidence missing' }
$baseline = [xml]($baselineText.TrimStart([char]0xfeff))
$names = @($baseline.testsuites.testsuite | ForEach-Object { $class=$_.name; $_.testcase | ForEach-Object { "${class}::$($_.name)" } })
if ($names.Count -ne 154 -or @($names | Where-Object { $_ -notin $cases.name }).Count -ne 0 -or @($cases | Group-Object name | Where-Object Count -gt 1).Count -ne 0) { throw 'Baseline cases changed or duplicate case names' }
$before = @()
$baselineSources = ((& git -C $project show '5e5fcec:evidence/verification/source-sha256.json') -join "`n").TrimStart([char]0xfeff) | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Baseline source hashes missing' }
$handlerFile = 'src/main/java/dev/mentoring/orders/exception/ApiExceptionHandler.java'
foreach ($directory in @($BeforeUnitRun, $BeforeHttpRun)) {
    Test-Cleanup $directory
    $inputSources = @(Read-Json $directory 'source-sha256.json')
    if (($inputSources | Where-Object file -eq $handlerFile).sha256Utf8Lf -ne ($baselineSources | Where-Object file -eq $handlerFile).sha256Utf8Lf) { throw 'Reproduction handler differs from baseline' }
    foreach ($testFile in @('src/test/java/dev/mentoring/orders/OrderHttpIntegrationTest.java','src/test/java/dev/mentoring/orders/exception/ApiExceptionHandlerTest.java')) {
        if (($inputSources | Where-Object file -eq $testFile).sha256Utf8Lf -ne ($current | Where-Object file -eq $testFile).sha256Utf8Lf) { throw 'Reproduction tests changed' }
    }
    $before += [ordered]@{ run=(Split-Path $directory -Leaf); summary=(Read-Json $directory 'summary.json' | Select-Object tests,failures,errors,skipped);
        failures=@(Read-Cases $directory | Where-Object failed | Select-Object name,message) }
}
if ($before[0].summary.tests -ne 14 -or $before[0].summary.failures -ne 3 -or $before[1].summary.tests -ne 56 -or $before[1].summary.failures -ne 2) { throw 'Unexpected reproduction scope' }
$query = Read-Json $QueryRun 'query-index-results.json'
$queryCleanup = Read-Json $QueryRun 'cleanup.json'
if ($query.plans.Count -ne 24 -or $queryCleanup.running -or -not $queryCleanup.stopped) { throw 'Incomplete query comparison' }
# 쿼리 검사는 HTTP 오류 변환과 별개. SQL/스키마/수집기가 동일한지 파일별 해시로 확인.
$querySources = @(Read-Json $QueryRun 'source-sha256.json')
foreach ($name in @('scripts/query-index.cjs','src/main/resources/db/migration/V1__orders_and_inventory.sql','src/main/java/dev/mentoring/orders/repository/OrderRepository.java')) {
    if (($querySources | Where-Object file -eq $name).sha256Utf8Lf -ne ($current | Where-Object file -eq $name).sha256Utf8Lf) { throw "Query input changed: $name" }
}
$destination = Join-Path $project 'evidence/verification'
$http = @(Read-Json $SuiteRun 'http-observations.json' | Where-Object scenario -in @('method-allow-header','body-type-accept-header','aged-key-cancelled-replay'))
if ($http.Count -ne 3) { throw 'Missing new HTTP observations' }
$record = [ordered]@{ baselineCommit='5e5fcec'; baselineChecks=154; retainedBaselineChecks=$names.Count;
    regular=[ordered]@{ run=(Split-Path $SuiteRun -Leaf); tests=$suiteSummary.tests; addedChecks=6; failures=$suiteSummary.failures; errors=$suiteSummary.errors; skipped=$suiteSummary.skipped };
    reproduction=$before; http=$http;
    databaseRecovery=[ordered]@{ run=(Split-Path $RecoveryRun -Leaf); tests=$recoverySummary.tests; failures=$recoverySummary.failures;
        events=@(Read-Json $RecoveryRun 'database-recovery.json') };
    query=[ordered]@{ run=(Split-Path $QueryRun -Leaf); environment=(Read-Json $QueryRun 'environment.json'); results=$query; stopped=[bool]$queryCleanup.stopped };
    scope=@('Latest source snapshot belongs to the 160-case suite and 15 separate DB-crash checks.',
        'Query plans use matching schema/repository/query collector; unrelated HTTP handler differs from query-run snapshot.',
        'backend-review.json retains the earlier 154-case and k6 source version; load was not rerun for the header fix.',
        'No record deletion/expiry, multi-server shared limiter, production capacity or locking-method comparison added.') }
$record | ConvertTo-Json -Depth 25 | Set-Content -LiteralPath (Join-Path $destination 'http-and-index-review.json') -Encoding UTF8
Write-Output "Published follow-up: $($suiteSummary.tests) regular, $($recoverySummary.tests) recovery, $($query.plans.Count) query plans"
