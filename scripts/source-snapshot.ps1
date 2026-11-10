$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$files = @(Get-ChildItem -LiteralPath (Join-Path $project 'src'), (Join-Path $project 'scripts'), (Join-Path $project '.github') -Recurse -File | Where-Object { $_.Extension -in '.java','.sql','.yml','.ps1','.cjs' })
$files += Get-Item -LiteralPath (Join-Path $project 'build.gradle'), (Join-Path $project 'settings.gradle'), (Join-Path $project 'gradle.properties'), (Join-Path $project 'gradle/wrapper/gradle-wrapper.properties')
$sha = [Security.Cryptography.SHA256]::Create()
try {
    foreach ($file in $files | Sort-Object FullName) {
        $content = [IO.File]::ReadAllText($file.FullName).Replace("`r`n", "`n")
        [ordered]@{ file = [IO.Path]::GetRelativePath($project, $file.FullName).Replace('\','/'); sha256Utf8Lf = [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($content))).ToLowerInvariant() }
    }
} finally { $sha.Dispose() }
