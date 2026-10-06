param(
    [string]$Serial = 'emulator-5554',
    [ValidateSet('Debug','Release')][string]$Variant = 'Debug',
    [ValidateSet('ImeSmokeInstrumentation','ImeV2Instrumentation','TranslationModelInstrumentation')][string]$Runner = 'ImeV2Instrumentation',
    [switch]$SkipBuild,
    [switch]$ModelsAvailable
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if (-not $SkipBuild) { & "$PSScriptRoot\build.ps1" -Variant $Variant -Test -AndroidTest }
$adb = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
& $adb -s $Serial get-state
if ($LASTEXITCODE -ne 0) { throw 'Requested Android emulator/device is unavailable.' }
$buildText = [IO.File]::ReadAllText((Join-Path $projectRoot 'app\build.gradle'))
$version = [regex]::Match($buildText, "versionName\s+'([^']+)'").Groups[1].Value
if (-not $version) { throw 'Could not read Android versionName.' }
$apk = if ($Variant -eq 'Release') { Join-Path $projectRoot "releases\Qingyu-$version.apk" } else { Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk' }
$testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
foreach ($file in @($apk,$testApk)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Missing APK: $file. Run without -SkipBuild first." }
    & $adb -s $Serial install -r $file
    if ($LASTEXITCODE -ne 0) { throw "Install failed: $file" }
}
& $adb -s $Serial shell ime enable com.qingyu.ime/.QingyuImeService
& $adb -s $Serial shell ime set com.qingyu.ime/.QingyuImeService
if ($LASTEXITCODE -ne 0) { throw 'Could not select the test IME.' }
$arguments = @('-s',$Serial,'shell','am','instrument','-w')
if ($ModelsAvailable) { $arguments += @('-e','models_available','true') }
$arguments += "com.qingyu.ime.test/com.qingyu.ime.$Runner"
& $adb @arguments | Tee-Object -Variable instrumentationLines
$result = $instrumentationLines -join "`n"
$evidence = "UTC: $([DateTime]::UtcNow.ToString('o'))`nVersion: $version`nVariant: $Variant`nRunner: $Runner`nModels available branch: $($ModelsAvailable.IsPresent)`nSerial: $Serial`nAPK SHA256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)`n`n$result`n"
$qa = Join-Path $projectRoot "releases\qa\v$version"
New-Item -ItemType Directory -Force -Path $qa | Out-Null
$stem = if ($Runner -eq 'ImeV2Instrumentation') { 'ime-v2-tests' } elseif ($Runner -eq 'TranslationModelInstrumentation') { 'translation-model-tests' } else { 'ime-baseline-tests' }
if ($ModelsAvailable -and $Runner -eq 'ImeV2Instrumentation') { $stem += '-with-models' }
$marker = if ($Runner -eq 'ImeV2Instrumentation') { 'ALL_V2_IME_CHECKS_PASS' } elseif ($Runner -eq 'TranslationModelInstrumentation') { 'ALL_MODEL_CHECKS_PASS' } else { 'ALL_IME_CHECKS_PASS' }
if ($result -notmatch $marker) {
    $failed = Join-Path $qa "$stem-$($Variant.ToLowerInvariant())-failed.txt"
    [IO.File]::WriteAllText($failed, $evidence, [Text.UTF8Encoding]::new($false))
    throw "IME integration checks failed; see $failed. Previous passing evidence is preserved."
}
[IO.File]::WriteAllText((Join-Path $qa "$stem-$($Variant.ToLowerInvariant()).txt"), $evidence, [Text.UTF8Encoding]::new($false))
$images = if ($Runner -eq 'ImeV2Instrumentation') { @('v2-pinyin.png','v2-nine.png','v2-detail.png','v2-clipboard.png','v2-dark.png') } elseif ($Runner -eq 'TranslationModelInstrumentation') { @() } else { @('ime-light.png','ime-dark.png') }
foreach ($name in $images) { & $adb -s $Serial pull "/sdcard/Android/data/com.qingyu.ime/files/$name" (Join-Path $qa $name) }
