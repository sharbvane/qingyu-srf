param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
$checkRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$checkDirectory = Join-Path $checkRoot '.tools\signing-restore-check'
if (Test-Path -LiteralPath $checkDirectory) { throw 'The restore-check directory already exists; check its contents before reusing it.' }
$backup = Get-ChildItem -LiteralPath (Join-Path $checkRoot 'release-signing\backups') -Filter '*.zip' |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $backup) { throw 'Create a signing backup before checking recovery.' }
$oldApk = Join-Path $checkRoot 'releases\Qingyu-0.4.0.apk'
$oldHash = (Get-FileHash -LiteralPath $oldApk -Algorithm SHA256).Hash
$oldKey = Join-Path $checkRoot '.tools\android-user\debug.keystore'
$oldKeyHash = (Get-FileHash -LiteralPath $oldKey -Algorithm SHA256).Hash
$arguments = @{}
if ($JavaHome) { $arguments['JavaHome'] = $JavaHome }
& "$PSScriptRoot\release-signing.ps1" -Action Check @arguments
New-Item -ItemType Directory -Path $checkDirectory | Out-Null
try {
    foreach ($directory in @('scripts', 'docs', '.tools\android-sdk\build-tools\35.0.0\lib')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $checkDirectory $directory) | Out-Null
    }
    foreach ($file in @('scripts\release-signing.ps1', 'scripts\resolve-java.ps1', 'docs\release-signing-policy.json',
        '.tools\android-sdk\build-tools\35.0.0\apksigner.bat', '.tools\android-sdk\build-tools\35.0.0\lib\apksigner.jar')) {
        Copy-Item -LiteralPath (Join-Path $checkRoot $file) -Destination (Join-Path $checkDirectory $file)
    }
    $helper = Join-Path $checkDirectory 'scripts\release-signing.ps1'
    & $helper -Action Restore -BackupFile $backup.FullName @arguments
    & $helper -Action Check @arguments
    Write-Output 'PASS: portable backup restores both keys, credentials and authenticated lineage under private ACL.'
    $expectedFailure = $false
    try { & $helper -Action Initialize @arguments } catch { $expectedFailure = $true }
    if (-not $expectedFailure) { throw 'Initialize replaced an existing identity.' }
    Write-Output 'PASS: initialization refuses to regenerate an existing signing identity.'
    $expectedFailure = $false
    try { & "$PSScriptRoot\release-signing.ps1" -Action Sign -Apk $oldApk @arguments } catch { $expectedFailure = $true }
    if (-not $expectedFailure -or (Get-FileHash -LiteralPath $oldApk -Algorithm SHA256).Hash -ne $oldHash -or
        (Get-FileHash -LiteralPath $oldKey -Algorithm SHA256).Hash -ne $oldKeyHash) { throw 'Original release or compatibility key changed.' }
    Write-Output 'PASS: published APKs and the original debug keystore are preserved.'
    # Simulate a missing key in the recovered copy, never in the working signing directory.
    $recoveredKey = Join-Path $checkDirectory 'release-signing\qingyu-release.p12'
    Move-Item -LiteralPath $recoveredKey -Destination "$recoveredKey.missing"
    $expectedFailure = $false
    try { & $helper -Action Check @arguments } catch { $expectedFailure = $true }
    if (-not $expectedFailure -or (Test-Path -LiteralPath $recoveredKey)) { throw 'Missing signing key did not fail closed.' }
    Write-Output 'PASS: missing Release key fails closed and is not recreated.'
    Write-Output 'RELEASE_SIGNING_RECOVERY_CHECKS_PASS'
} finally {
    $resolved = [IO.Path]::GetFullPath((Get-Item -LiteralPath $checkDirectory).FullName)
    $allowed = [IO.Path]::GetFullPath((Join-Path $checkRoot '.tools\signing-restore-check'))
    if ($resolved -ne $allowed -or -not $resolved.StartsWith($checkRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refused to clean a recovery-check path outside the workspace.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
