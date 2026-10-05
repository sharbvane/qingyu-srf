[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554',
    [ValidateSet('arm64-v8a','armeabi-v7a','x86_64')][string]$Abi = 'x86_64',
    [switch]$SkipNativeBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = Join-Path $projectRoot '.tools/android-sdk'
$adbEngine = Join-Path $sdkRoot 'platform-tools/adb.exe'
$outputRoot = Join-Path $projectRoot '.tools/engine-build'
$remoteRoot = '/data/local/tmp/qingyu-engine'

function Invoke-AdbEngine {
    param([string[]]$Arguments)
    $result = & $adbEngine -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed ($LASTEXITCODE): $($Arguments -join ' ')" }
    return $result
}

if (!$SkipNativeBuild) { & (Join-Path $PSScriptRoot 'build-native.ps1') -Abis @($Abi) -Smoke }
$classDirectory = Join-Path $outputRoot 'adapter-classes'
New-Item -ItemType Directory -Force -Path $classDirectory | Out-Null
$coreSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core/src/main/java/com/qingyu/core') -Filter '*.java' -File |
    ForEach-Object { $_.FullName })
& javac.exe -encoding UTF-8 -source 17 -target 17 -d $classDirectory @coreSources (Join-Path $PSScriptRoot 'EngineAdapterSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Java adapter smoke compile failed' }
$classFiles = @(Get-ChildItem -LiteralPath $classDirectory -Filter '*.class' -File -Recurse | ForEach-Object { $_.FullName })
$dexJar = Join-Path $outputRoot 'engine-adapter-smoke.jar'
& java.exe -cp (Join-Path $sdkRoot 'build-tools/35.0.0/lib/d8.jar') com.android.tools.r8.D8 --min-api 26 `
    --lib (Join-Path $sdkRoot 'platforms/android-35/android.jar') --output $dexJar @classFiles
if ($LASTEXITCODE -ne 0) { throw 'Adapter DEX build failed' }

Invoke-AdbEngine -Arguments @('shell','mkdir','-p',$remoteRoot) | Out-Null
Invoke-AdbEngine -Arguments @('push',(Join-Path $projectRoot 'app/src/main/assets/pinyin/dict_pinyin.dat'),"$remoteRoot/dict_pinyin.dat")
Invoke-AdbEngine -Arguments @('push',(Join-Path $outputRoot "direct/$Abi/libqingyu_pinyin.so"),"$remoteRoot/libqingyu_pinyin.so")
Invoke-AdbEngine -Arguments @('push',(Join-Path $outputRoot "smoke/$Abi/engine-smoke"),"$remoteRoot/engine-smoke")
Invoke-AdbEngine -Arguments @('push',$dexJar,"$remoteRoot/engine-adapter-smoke.jar")
Invoke-AdbEngine -Arguments @('shell','chmod','700',"$remoteRoot/engine-smoke") | Out-Null
$nativeResult = Invoke-AdbEngine -Arguments @('shell',"$remoteRoot/engine-smoke", "$remoteRoot/dict_pinyin.dat", "$remoteRoot/user.dat")
$nativeResult | Set-Content -LiteralPath (Join-Path $projectRoot 'docs/engine-smoke-results.txt') -Encoding utf8
$nativeResult
$adapterCommand = "CLASSPATH=$remoteRoot/engine-adapter-smoke.jar app_process -Djava.library.path=$remoteRoot /system/bin com.qingyu.core.EngineAdapterSmoke $remoteRoot/dict_pinyin.dat $remoteRoot/adapter-user.dat"
$adapterResult = Invoke-AdbEngine -Arguments @('shell',$adapterCommand)
$adapterResult | Set-Content -LiteralPath (Join-Path $projectRoot 'docs/engine-adapter-results.txt') -Encoding utf8
$adapterResult
if (($nativeResult -join "`n") -notmatch 'ALL_ENGINE_CHECKS_PASS' -or ($adapterResult -join "`n") -notmatch 'ALL_ADAPTER_CHECKS_PASS') {
    throw 'An expected smoke success marker was missing'
}
