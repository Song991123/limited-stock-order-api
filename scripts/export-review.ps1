param([Parameter(Mandatory)][string]$SuiteRun, [Parameter(Mandatory)][string]$RecoveryRun,
      [Parameter(Mandatory)][string]$LoadRun, [Parameter(Mandatory)][string]$BeforeRecoveryRun)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$rawRoot = [IO.Path]::GetFullPath((Join-Path $project 'evidence/raw')) + [IO.Path]::DirectorySeparatorChar
$current = @(@(& (Join-Path $PSScriptRoot 'source-snapshot.ps1')) | ConvertTo-Json -Depth 4 | ConvertFrom-Json)
function Read-Record([string]$directory, [string]$name) {
    $resolved = (Resolve-Path -LiteralPath $directory).Path
    if (-not $resolved.StartsWith($rawRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Use this project raw evidence only' }
    Get-Content -Raw -LiteralPath (Join-Path $resolved $name) | ConvertFrom-Json
}
function Require-Current([string]$directory, [switch]$AllowPublisherChange) {
    $inputFiles = @(Read-Record $directory 'source-sha256.json')
    $changed = @(Compare-Object $inputFiles $current -Property file,sha256Utf8Lf | Select-Object -ExpandProperty file -Unique)
    if (-not $changed.Count) { return }
    if (-not $AllowPublisherChange -or $changed.Count -ne 1 -or $changed[0] -ne 'scripts/export-review.ps1') {
        throw 'Review sources changed after the run'
    }
    [ordered]@{ file=$changed[0]; recordedSha256=($inputFiles | Where-Object file -eq $changed[0]).sha256Utf8Lf;
        publishedSha256=($current | Where-Object file -eq $changed[0]).sha256Utf8Lf;
        reason='Only the post-processing publisher changed after load execution. Application, measurement collector, k6 script and test inputs are identical. Raw source snapshot is preserved.' }
}
function Cases([string]$directory) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $directory 'junit-xml') -Filter 'TEST-*.xml') {
        $suite = ([xml](Get-Content -Raw -LiteralPath $file.FullName)).testsuite
        foreach ($case in $suite.testcase) { if ($null -ne $case) { "$($case.classname)|$($case.name)" } }
    }
}
foreach ($directory in @($SuiteRun, $RecoveryRun)) { Require-Current $directory }
$publicationChange = Require-Current $LoadRun -AllowPublisherChange
$suite = Read-Record $SuiteRun 'summary.json'
$recovery = Read-Record $RecoveryRun 'summary.json'
$load = Read-Record $LoadRun 'summary.json'
$before = Read-Record $BeforeRecoveryRun 'summary.json'
foreach ($summary in @($suite, $recovery)) {
    if ($summary.buildExit -ne 0 -or $summary.tests -lt 1 -or $summary.failures -or $summary.errors -or $summary.skipped) { throw 'Incomplete JUnit run' }
}
if (-not $recovery.databaseRecovery -or $suite.databaseRecovery -or -not $load.completed -or $load.smoke -or $load.runs -ne 24) { throw 'Wrong run type or incomplete load matrix' }
foreach ($directory in @($SuiteRun, $RecoveryRun)) {
    if ((Read-Record $directory 'isolated-base-cleanup.json').pidFileExists) { throw 'Test database not stopped' }
}
$cleanup = Read-Record $LoadRun 'cleanup.json'
if ($cleanup.databaseRunning -or -not $cleanup.serverStopped) { throw 'Load processes not stopped' }
$baselineText = & git -C $project show '6e74951:evidence/verification/junit-summary.xml'
if ($LASTEXITCODE -ne 0) { throw 'Missing baseline verification' }
$baseline = [xml]::new()
$baselineStream = [IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes(($baselineText -join "`n")))
try { $baseline.Load($baselineStream) } finally { $baselineStream.Dispose() }
$baselineCases = @($baseline.testsuites.testsuite | ForEach-Object { foreach ($case in $_.testcase) { if ($null -ne $case) { "$($case.classname)|$($case.name)" } } })
$currentCases = @(Cases $SuiteRun)
$missing = @($baselineCases | Where-Object { $_ -notin $currentCases })
if ($missing.Count -or $baselineCases.Count -ne 135) { throw 'Baseline checks were removed or renamed' }
if (@($currentCases | Group-Object | Where-Object Count -gt 1).Count) { throw 'Ambiguous JUnit case names' }
$recoveryEvents = @(Read-Record $RecoveryRun 'database-recovery.json')
if ($recoveryEvents.Count -ne 15 -or @($recoveryEvents | Where-Object { -not $_.sameJvm }).Count) { throw 'Incomplete same-JVM recovery evidence' }
$rows = @(Read-Record $LoadRun 'load-results.json')
if ($rows.Count -ne 24) { throw 'Load observations missing' }
foreach ($row in $rows) {
    if ($row.database.orders -ne $row.createdAll -or $row.database.requests -ne $row.createdAll -or
        $row.database.inventoryViolations -or $row.database.requestViolations -or $row.database.orderViolations -or
        $row.metrics.unexpected_responses.values.count) { throw 'Load invariant mismatch' }
}
$beforeEvents = @(Read-Record $BeforeRecoveryRun 'database-recovery.json')
$result = [ordered]@{
    baselineCommit='6e74951'; baselineChecks=$baselineCases.Count; retainedBaselineChecks=$baselineCases.Count
    regularSuite=[ordered]@{ run=(Split-Path $SuiteRun -Leaf); tests=$suite.tests; additionalChecks=($suite.tests-$baselineCases.Count); failures=$suite.failures; errors=$suite.errors; skipped=$suite.skipped }
    databaseRecovery=[ordered]@{ beforeRun=(Split-Path $BeforeRecoveryRun -Leaf); beforeTests=$before.tests; beforeFailures=$before.failures;
        afterRun=(Split-Path $RecoveryRun -Leaf); afterTests=$recovery.tests; afterFailures=$recovery.failures;
        beforeEvents=$beforeEvents; afterEvents=$recoveryEvents;
        scope='New owned PostgreSQL cluster stopped with immediate mode. Same application JVM; fsync and synchronous_commit remain enabled. Local task, not CI.' }
    load=[ordered]@{ run=(Split-Path $LoadRun -Leaf); publicationScriptChange=$publicationChange; environment=(Read-Record $LoadRun 'environment.json'); runs=$rows;
        scope='Same-PC k6 HTTP baseline, not production capacity, locking-method comparison or before/after performance claim'; cleanup=$cleanup }
    unverified=@('production capacity','long-running load','shared multi-server admission','record retention expiry','other databases and deployment infrastructure')
}
$result | ConvertTo-Json -Depth 18 | Set-Content -LiteralPath (Join-Path $project 'evidence/verification/backend-review.json') -Encoding UTF8
Write-Output "Published review: $($suite.tests) regular JUnit, $($recovery.tests) separate recovery, $($rows.Count) load runs"
