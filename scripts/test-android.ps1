param([string]$Serial = 'emulator-5554', [ValidateSet('Debug','Release')][string]$Variant = 'Debug', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if (-not $SkipBuild) { & "$PSScriptRoot\build.ps1" -Variant $Variant -Test -AndroidTest }
$adb = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
& $adb -s $Serial get-state
if ($LASTEXITCODE -ne 0) { throw 'Requested Android emulator/device is unavailable.' }
$apk = if ($Variant -eq 'Release') { Join-Path $projectRoot 'releases\Qingyu-0.1.0.apk' } else { Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk' }
$testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
foreach ($file in @($apk,$testApk)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing APK: $file. Run without -SkipBuild first." }
    & $adb -s $Serial install -r $file
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $file" }
}
& $adb -s $Serial shell ime enable com.qingyu.ime/.QingyuImeService
& $adb -s $Serial shell ime set com.qingyu.ime/.QingyuImeService
if ($LASTEXITCODE -ne 0) { throw 'Could not select the test IME.' }
$result = (& $adb -s $Serial shell am instrument -w com.qingyu.ime.test/com.qingyu.ime.ImeSmokeInstrumentation) -join "`n"
$evidence = "UTC: $([DateTime]::UtcNow.ToString('o'))`nVariant: $Variant`nSerial: $Serial`nAPK SHA256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)`n`n$result`n"
$qa = Join-Path $projectRoot 'releases\qa'
New-Item -ItemType Directory -Force -Path $qa | Out-Null
Write-Output $result
if ($result -notmatch 'ALL_IME_CHECKS_PASS') {
    $failed = Join-Path $projectRoot '.tools\logs\ime-tests-failed.txt'
    [IO.File]::WriteAllText($failed, $evidence, [Text.UTF8Encoding]::new($false))
    throw "IME integration checks failed; see $failed. Previous passing evidence is preserved."
}
[IO.File]::WriteAllText((Join-Path $qa 'ime-tests.txt'), $evidence, [Text.UTF8Encoding]::new($false))
foreach ($name in @('ime-light.png','ime-dark.png')) { & $adb -s $Serial pull "/sdcard/Android/data/com.qingyu.ime/files/$name" (Join-Path $qa $name) }

