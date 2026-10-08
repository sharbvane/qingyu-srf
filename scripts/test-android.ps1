param(
    [string]$Serial = 'emulator-5554',
    [ValidateSet('Debug','Release')][string]$Variant = 'Debug',
    [ValidateSet('ImeSmokeInstrumentation','ImeV2Instrumentation','ImeV3Instrumentation','ImeV4Instrumentation','ImeV5Instrumentation','ImeV6Instrumentation','TranslationModelInstrumentation','AppUpdateInstrumentation')][string]$Runner = 'ImeV6Instrumentation',
    [switch]$SkipBuild,
    [switch]$ModelsAvailable,
    [switch]$ModelsOnly,
    [switch]$RedownloadModels,
    [switch]$WebCheck,
    [switch]$V4Only
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
if ($Variant -eq 'Release' -and $Runner -eq 'TranslationModelInstrumentation') { throw 'The model API helper uses methods optimized by R8. Run it with -Variant Debug; final Release model behavior is checked by ImeV3Instrumentation through the real IME UI.' }
if ($Variant -eq 'Release' -and $Runner -eq 'AppUpdateInstrumentation') { throw 'Run the direct update API helper with -Variant Debug; Release update entry is checked through ImeV4Instrumentation.' }
if (-not $SkipBuild) { & "$PSScriptRoot\build.ps1" -Variant $Variant -Test -AndroidTest }
$adb = Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'
& $adb -s $Serial get-state
if ($LASTEXITCODE -ne 0) { throw 'Requested Android emulator/device is unavailable.' }
$fontScale = (& $adb -s $Serial shell settings get system font_scale).Trim()
$buildText = [IO.File]::ReadAllText((Join-Path $projectRoot 'app\build.gradle'))
$version = [regex]::Match($buildText, "versionName\s+'([^']+)'").Groups[1].Value
if (-not $version) { throw 'Could not read Android versionName.' }
$apk = if ($Variant -eq 'Release') { Join-Path $projectRoot "releases\Qingyu-$version.apk" } else { Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk' }
$testApk = Join-Path $projectRoot "app\build\outputs\apk\androidTest\$($Variant.ToLowerInvariant())\app-$($Variant.ToLowerInvariant())-androidTest.apk"
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
if ($RedownloadModels) { if($Runner -ne 'TranslationModelInstrumentation'){throw 'RedownloadModels requires TranslationModelInstrumentation.'};$arguments += @('-e','redownload','true') }
if ($WebCheck) { if($Runner -ne 'AppUpdateInstrumentation'){throw 'WebCheck requires AppUpdateInstrumentation.'};$arguments += @('-e','webcheck','true') }
if ($ModelsOnly) { if ($Runner -ne 'ImeV3Instrumentation') { throw 'ModelsOnly requires ImeV3Instrumentation.' };$arguments += @('-e','models_only','true') }
if ($V4Only) { if ($Runner -notin @('ImeV4Instrumentation','ImeV5Instrumentation','ImeV6Instrumentation')) { throw 'V4Only requires ImeV4Instrumentation or ImeV5Instrumentation.' };$arguments += @('-e','v4_only','true') }
$arguments += "com.qingyu.ime.test/com.qingyu.ime.$Runner"
& $adb @arguments | Tee-Object -Variable instrumentationLines
$result = $instrumentationLines -join "`n"
$evidence = "UTC: $([DateTime]::UtcNow.ToString('o'))`nVersion: $version`nVariant: $Variant`nRunner: $Runner`nSystem font scale: $fontScale`nModels available branch: $($ModelsAvailable.IsPresent)`nModels only branch: $($ModelsOnly.IsPresent)`nV4 only branch: $($V4Only.IsPresent)`nSerial: $Serial`nAPK SHA256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)`n`n$result`n"
$qa = Join-Path $projectRoot "releases\qa\v$version"
New-Item -ItemType Directory -Force -Path $qa | Out-Null
$stem = if ($Runner -eq 'ImeV6Instrumentation') { 'ime-v6-tests' } elseif ($Runner -eq 'ImeV5Instrumentation') { 'ime-v5-tests' } elseif ($Runner -eq 'ImeV4Instrumentation') { 'ime-v4-tests' } elseif ($Runner -eq 'ImeV3Instrumentation') { 'ime-v3-tests' } elseif ($Runner -eq 'ImeV2Instrumentation') { 'ime-v2-tests' } elseif ($Runner -eq 'TranslationModelInstrumentation') { 'translation-model-tests' } elseif ($Runner -eq 'AppUpdateInstrumentation') { 'app-update-tests' } else { 'ime-baseline-tests' }
if ($V4Only) { $stem += '-new-behavior' }
if ($WebCheck) { $stem += '-with-web' }
if ($ModelsAvailable -and $Runner -eq 'ImeV2Instrumentation') { $stem += '-with-models' }
$marker = if ($Runner -eq 'ImeV6Instrumentation') { 'ALL_V6_IME_CHECKS_PASS' } elseif ($Runner -eq 'ImeV5Instrumentation') { 'ALL_V5_IME_CHECKS_PASS' } elseif ($Runner -eq 'ImeV4Instrumentation') { 'ALL_V4_IME_CHECKS_PASS' } elseif ($Runner -eq 'ImeV3Instrumentation') { 'ALL_V3_IME_CHECKS_PASS' } elseif ($Runner -eq 'ImeV2Instrumentation') { 'ALL_V2_IME_CHECKS_PASS' } elseif ($Runner -eq 'TranslationModelInstrumentation') { 'ALL_MODEL_CHECKS_PASS' } elseif ($Runner -eq 'AppUpdateInstrumentation') { 'ALL_APP_UPDATE_CHECKS_PASS' } else { 'ALL_IME_CHECKS_PASS' }
if ($ModelsOnly) { $stem='model-management-tests';$marker='ALL_MODEL_MANAGEMENT_CHECKS_PASS' }
if ($RedownloadModels) { $stem+='-redownload' }
if ($result -notmatch $marker) {
    $failed = Join-Path $qa "$stem-$($Variant.ToLowerInvariant())-failed.txt"
    [IO.File]::WriteAllText($failed, $evidence, [Text.UTF8Encoding]::new($false))
    throw "IME integration checks failed; see $failed. Previous passing evidence is preserved."
}
[IO.File]::WriteAllText((Join-Path $qa "$stem-$($Variant.ToLowerInvariant()).txt"), $evidence, [Text.UTF8Encoding]::new($false))
$images = if ($Runner -in @('ImeV4Instrumentation','ImeV5Instrumentation','ImeV6Instrumentation')) { @('v4-idle.png','v4-pinyin.png','v4-expanded.png','v4-update.png');if($Runner -in @('ImeV5Instrumentation','ImeV6Instrumentation')){@('v5-pinyin.png','v5-expanded.png')};if($Runner -eq 'ImeV6Instrumentation'){@('v6-pinyin.png','v6-expanded.png','v6-detail.png')};if($ModelsAvailable){@('v4-models-deleted.png','v4-models-restored.png')};if(-not $V4Only){@('v3-letters.png','v3-models.png','v2-nine.png','v2-detail.png','v2-clipboard.png','v2-dark.png')} } elseif ($Runner -eq 'ImeV3Instrumentation') { @('v3-expanded.png','v3-letters.png','v3-pinyin.png','v3-models.png','v2-nine.png','v2-detail.png','v2-clipboard.png','v2-dark.png') } elseif ($Runner -eq 'ImeV2Instrumentation') { @('v2-pinyin.png','v2-nine.png','v2-detail.png','v2-clipboard.png','v2-dark.png') } elseif ($Runner -in @('TranslationModelInstrumentation','AppUpdateInstrumentation')) { @() } else { @('ime-light.png','ime-dark.png') }
if ($ModelsOnly) { $images=@('v3-models.png') }
foreach ($name in $images) { & $adb -s $Serial pull "/sdcard/Android/data/com.qingyu.ime/files/$name" (Join-Path $qa $name) }
