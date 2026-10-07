[CmdletBinding()]
param([string]$Serial = 'emulator-5554', [switch]$SkipCompile)
$ErrorActionPreference = 'Stop'
$pinyinRoot = Split-Path -Parent $PSScriptRoot
$pinyinSdk = Join-Path $pinyinRoot '.tools/android-sdk'
$pinyinAdb = Join-Path $pinyinSdk 'platform-tools/adb.exe'
$pinyinSource = Join-Path $pinyinRoot 'app/src/main/cpp'
$pinyinOutput = Join-Path $pinyinRoot '.tools/pinyin-model'
New-Item -ItemType Directory -Force -Path $pinyinOutput | Out-Null
& python.exe (Join-Path $PSScriptRoot 'build_pinyin_dictionary.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinyin source preparation failed' }
$pinyinBuilder = Join-Path $pinyinOutput 'pinyin-model-builder'
if (!$SkipCompile) {
    $pinyinCompiler = Join-Path $pinyinSdk 'ndk/28.1.13356709/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
    $pinyinSources = @(Get-ChildItem -LiteralPath (Join-Path $pinyinSource 'aosp/share') -Filter '*.cpp' -File | ForEach-Object { $_.FullName })
    & $pinyinCompiler --target=x86_64-linux-android26 -fPIE -pie -std=c++11 -O2 -D___BUILD_MODEL___ -static-libstdc++ '-Wl,-z,max-page-size=16384' "-I$(Join-Path $pinyinSource 'compat')" "-I$(Join-Path $pinyinSource 'aosp/include')" @pinyinSources (Join-Path $PSScriptRoot 'pinyin-model-builder.cpp') -llog -lm -o $pinyinBuilder
    if ($LASTEXITCODE -ne 0) { throw 'Native model compiler build failed' }
}
function Invoke-PinyinAdb([string[]]$Arguments) {
    & $pinyinAdb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Model build adb failed: $($Arguments -join ' ')" }
}
$pinyinRemote = '/data/local/tmp/qingyu-model'
Invoke-PinyinAdb @('shell','mkdir','-p',$pinyinRemote)
foreach ($pinyinFile in @('pinyin-model-builder','raw-utf16.txt','valid-hanzi-utf16.txt')) {
    Invoke-PinyinAdb @('push',(Join-Path $pinyinOutput $pinyinFile),"$pinyinRemote/$pinyinFile")
}
Invoke-PinyinAdb @('shell','chmod','700',"$pinyinRemote/pinyin-model-builder")
Invoke-PinyinAdb @('shell',"$pinyinRemote/pinyin-model-builder","$pinyinRemote/raw-utf16.txt","$pinyinRemote/valid-hanzi-utf16.txt","$pinyinRemote/dict_pinyin.dat")
Invoke-PinyinAdb @('pull',"$pinyinRemote/dict_pinyin.dat",(Join-Path $pinyinRoot 'app/src/main/assets/pinyin/dict_pinyin.dat'))
& python.exe (Join-Path $PSScriptRoot 'build_pinyin_dictionary.py') --record-output
if ($LASTEXITCODE -ne 0) { throw 'Pinyin output recording failed' }
