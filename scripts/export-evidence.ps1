param([string]$RunDirectory)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$rawRoot = Join-Path $project 'evidence/raw'
if (-not $RunDirectory) {
    $RunDirectory = (Get-ChildItem -LiteralPath $rawRoot -Directory | Sort-Object Name -Descending | Where-Object {
        $summary = Join-Path $_.FullName 'summary.json'
        if (-not (Test-Path -LiteralPath $summary)) { return $false }
        $record = Get-Content -LiteralPath $summary -Raw | ConvertFrom-Json
        $record.buildExit -eq 0 -and -not $record.databaseRecovery
    } | Select-Object -First 1).FullName
}
$resolved = (Resolve-Path -LiteralPath $RunDirectory).Path
if (-not $resolved.StartsWith(([IO.Path]::GetFullPath($rawRoot) + [IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)) { throw 'Use an evidence/raw run from this project' }
$summary = Get-Content -LiteralPath (Join-Path $resolved 'summary.json') -Raw | ConvertFrom-Json
if ($summary.databaseRecovery) { throw 'Use the regular full suite for the main JUnit publication' }
if ($summary.buildExit -ne 0 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.tests -lt 1) { throw 'Only a successful completed run can be exported' }
$inputFiles = @(Get-Content -LiteralPath (Join-Path $resolved 'source-sha256.json') -Raw | ConvertFrom-Json)
$current = @(& (Join-Path $PSScriptRoot 'source-snapshot.ps1'))
if (($inputFiles | ConvertTo-Json -Compress) -ne ($current | ConvertTo-Json -Compress)) { throw 'Sources changed since the recorded test run; run tests again before exporting' }
$actorFiles = @('actor-order-journeys.json', 'actor-access.json', 'actor-admission.json')
foreach ($name in @('http-observations.json', 'process-recovery.json', 'normal-startup.json', 'isolated-base-cleanup.json', 'two-servers.json') + $actorFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $resolved $name) -PathType Leaf)) {
        throw "Incomplete full-suite evidence: $name is missing; published evidence was not changed"
    }
}
$destination = Join-Path $project 'evidence/verification'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$inputFiles | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $destination 'source-sha256.json') -Encoding UTF8
$observations = Get-Content -LiteralPath (Join-Path $resolved 'http-observations.json') -Raw | ConvertFrom-Json
$observations | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'observations.json') -Encoding UTF8
foreach ($name in $actorFiles) {
    $actorEvidence = Join-Path $resolved $name
    if (Test-Path -LiteralPath $actorEvidence) {
        $actorRows = @(Get-Content -LiteralPath $actorEvidence -Raw | ConvertFrom-Json)
        if ($name -eq 'actor-access.json') {
            $actorRows = @($actorRows | Group-Object scenario | ForEach-Object {
                $events = @($_.Group | ForEach-Object { $_.observations })
                $checks = @($events | Where-Object phase -eq 'storage-check')
                $final = @($events | Where-Object phase -eq 'after-each')
                if ($final.Count -ne 1) { throw 'Each access scenario must have one final storage check' }
                $requests = @($events | Where-Object phase -eq 'http' | ForEach-Object {
                    $request = [ordered]@{ actor=$_.actor; method=$_.method; path=$_.path; http=$_.http }
                    foreach ($field in @('id', 'productId', 'quantity', 'status', 'code', 'tokenIssued')) {
                        if ($_.body -and $_.body.PSObject.Properties[$field]) { $request[$field] = $_.body.$field }
                    }
                    if ($_.body -is [array]) {
                        $request['returnedCount'] = $_.body.Count
                        $request['returnedIds'] = @($_.body | ForEach-Object { $_.id })
                    }
                    $request
                })
                [ordered]@{
                    scenario=$_.Name
                    requests=$requests
                    storageComparisons=$checks.Count
                    allStorageComparisonsUnchanged=($checks.Count -gt 0 -and @($checks | Where-Object { -not $_.unchanged }).Count -eq 0)
                    sessionEvents=@($events | Where-Object phase -in @('independent-sessions', 'delete-session-cookie', 'new-csrf-session'))
                    inventoryViolations=$final[0].inventoryViolations
                    requestViolations=$final[0].requestViolations
                    orderViolations=$final[0].orderViolations
                    finalDatabase=$final[0].database
                }
            })
        }
        ConvertTo-Json -InputObject $actorRows -Depth 12 |
            Set-Content -LiteralPath (Join-Path $destination $name) -Encoding UTF8
    }
}
$processCases = @(Get-Content -LiteralPath (Join-Path $resolved 'process-recovery.json') -Raw | ConvertFrom-Json | Where-Object { $_.stage -ne 'owned-jvm-terminated' })
$processCases | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'process-recovery.json') -Encoding UTF8
$normalStartup = Get-Content -LiteralPath (Join-Path $resolved 'normal-startup.json') -Raw | ConvertFrom-Json
@(Get-Content -Raw -LiteralPath (Join-Path $resolved 'two-servers.json') | ConvertFrom-Json) | ForEach-Object {
    [ordered]@{ scenario=$_.scenario; http=$_.http; database=$_.database; scope=$_.scope }
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $destination 'two-servers.json') -Encoding UTF8
[ordered]@{ profile=$normalStartup.profile; demoInitializerPresent=[bool]$normalStartup.demoInitializerPresent; buyers=[int]$normalStartup.buyers; products=[int]$normalStartup.products; unauthenticatedHttp=[int]$normalStartup.unauthenticatedHttp } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'normal-startup.json') -Encoding UTF8
$clean = Get-Content -LiteralPath (Join-Path $resolved 'isolated-base-cleanup.json') -Raw | ConvertFrom-Json
$xml = [xml]'<?xml version="1.0" encoding="utf-8"?><testsuites />'
$suites = @()
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $resolved 'junit-xml') -Filter 'TEST-*.xml' | Sort-Object Name) {
    $suite = ([xml](Get-Content -LiteralPath $file.FullName -Raw)).testsuite
    $node = $xml.CreateElement('testsuite')
    foreach ($attribute in @('name','tests','failures','errors','skipped','time')) { $node.SetAttribute($attribute, [string]$suite.$attribute) }
    foreach ($case in $suite.testcase) { $null = $node.AppendChild($xml.ImportNode($case, $false)) }
    $null = $xml.DocumentElement.AppendChild($node)
    $suites += [ordered]@{ name=$suite.name; tests=[int]$suite.tests; failures=[int]$suite.failures; errors=[int]$suite.errors; seconds=[double]::Parse($suite.time,[Globalization.CultureInfo]::InvariantCulture) }
}
foreach ($name in @('tests','failures','errors','skipped')) { $xml.DocumentElement.SetAttribute($name, [string]$summary.$name) }
$xml.Save((Join-Path $destination 'junit-summary.xml'))
[ordered]@{ testedOn=(Split-Path $resolved -Leaf); tests=$summary.tests; failures=$summary.failures; errors=$summary.errors; skipped=$summary.skipped; isolatedDatabase=[bool]$summary.isolatedDatabase; testDatabaseStopped=(-not $clean.pidFileExists); sourceSnapshot='source-sha256.json'; suites=$suites; scope=@('real HTTP','PostgreSQL 16.15','SQL fault injection','HTTP response loss','separate JVM termination and restart','normal profile startup without demo data'); unmeasured=@('locking-method performance comparison','production capacity','long-running load','database server crash recovery','record-retention capacity guard') } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'latest.json') -Encoding UTF8
Write-Output "Published representative verification evidence: $($summary.tests) tests"
