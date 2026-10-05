[CmdletBinding()]
param(
    [string]$NdkPath = '',
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64'),
    [switch]$Smoke,
    [switch]$PackagePrebuilt
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$NdkPath) { $NdkPath = Join-Path $projectRoot '.tools/android-sdk/ndk/28.1.13356709' }
$clangNative = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
if (!(Test-Path -LiteralPath $clangNative)) { throw "NDK compiler missing: $clangNative" }
$sourceRoot = Join-Path $projectRoot 'app/src/main/cpp'
$nativeSources = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'aosp/share') -Filter '*.cpp' -File |
    ForEach-Object { $_.FullName })
$targets = @{ 'arm64-v8a' = 'aarch64-linux-android26'; 'armeabi-v7a' = 'armv7a-linux-androideabi26'; 'x86_64' = 'x86_64-linux-android26' }
foreach ($abi in $Abis) {
    if (!$targets.ContainsKey($abi)) { throw "Unsupported ABI: $abi" }
    $outputDirectory = Join-Path $projectRoot ".tools/engine-build/direct/$abi"
    if ($PackagePrebuilt) { $outputDirectory = Join-Path $projectRoot "app/src/main/jniLibs/$abi" }
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $arguments = @("--target=$($targets[$abi])", '-shared', '-fPIC', '-std=c++11', '-O2',
        '-fvisibility=hidden', '-static-libstdc++', '-Wl,-z,max-page-size=16384',
        "-I$(Join-Path $sourceRoot 'compat')", "-I$(Join-Path $sourceRoot 'aosp/include')")
    $arguments += $nativeSources
    $arguments += @((Join-Path $sourceRoot 'qingyu_jni.cpp'), '-llog', '-lm', '-o', (Join-Path $outputDirectory 'libqingyu_pinyin.so'))
    & $clangNative @arguments
    if ($LASTEXITCODE -ne 0) { throw "Native decoder build failed for $abi" }
    Write-Output "Built $abi decoder"
    if ($Smoke) {
        $smokeDirectory = Join-Path $projectRoot ".tools/engine-build/smoke/$abi"
        New-Item -ItemType Directory -Force -Path $smokeDirectory | Out-Null
        $smokeArguments = @("--target=$($targets[$abi])", '-fPIE', '-pie', '-std=c++11', '-O2',
            '-static-libstdc++', '-Wl,-z,max-page-size=16384',
            "-I$(Join-Path $sourceRoot 'compat')", "-I$(Join-Path $sourceRoot 'aosp/include')")
        $smokeArguments += $nativeSources
        $smokeArguments += @((Join-Path $PSScriptRoot 'engine-smoke.cpp'), '-llog', '-lm', '-o', (Join-Path $smokeDirectory 'engine-smoke'))
        & $clangNative @smokeArguments
        if ($LASTEXITCODE -ne 0) { throw "Decoder smoke executable build failed for $abi" }
        Write-Output "Built $abi smoke executable"
    }
}
