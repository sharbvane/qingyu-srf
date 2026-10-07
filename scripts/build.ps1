param(
    [ValidateSet('Debug', 'Release')][string]$Variant = 'Release',
    [switch]$Test,
    [switch]$AndroidTest,
    [switch]$Install,
    [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$gradleVersion = '8.9'
. "$PSScriptRoot\resolve-java.ps1"
$JavaHome = Resolve-QingyuJavaHome -Requested $JavaHome
if (-not (Test-Path -LiteralPath "$projectRoot\.tools\gradle\gradle-$gradleVersion\bin\gradle.bat")) {
    throw 'The project toolchain is missing. Run scripts/setup-toolchain.ps1 first.'
}
if (-not (Test-Path -LiteralPath "$projectRoot\.tools\android-sdk\platforms\android-35\android.jar")) {
    throw 'The project Android SDK is missing. Run scripts/setup-toolchain.ps1 first.'
}
if ($Variant -eq 'Release') {
    & "$PSScriptRoot\release-signing.ps1" -Action Check -JavaHome $JavaHome
}

# The NDK and CMake must receive an ASCII workspace path on Windows.
$buildRoot = $projectRoot
$substDrive = $null
if ($projectRoot -match '[^\x00-\x7F]') {
    foreach ($letter in @('W', 'V', 'U', 'T', 'S', 'R', 'Q')) {
        if (-not (Test-Path "${letter}:\")) {
            & subst.exe "${letter}:" $projectRoot
            if ($LASTEXITCODE -ne 0) { throw 'Could not create the temporary ASCII workspace drive.' }
            $substDrive = "${letter}:"
            $buildRoot = "$substDrive\"
            break
        }
    }
    if (-not $substDrive) { throw 'No free drive letter is available for the Windows native build.' }
}

$envNames = @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','ANDROID_USER_HOME','ANDROID_AVD_HOME','GRADLE_USER_HOME','TEMP','TMP')
$savedEnv = @{}
foreach ($name in $envNames) { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$localProperties = Join-Path $projectRoot 'local.properties'
$savedLocal = if (Test-Path -LiteralPath $localProperties) { [IO.File]::ReadAllBytes($localProperties) } else { $null }
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = Join-Path $buildRoot '.tools\android-sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:ANDROID_USER_HOME = Join-Path $buildRoot '.tools\android-user'
    $env:ANDROID_AVD_HOME = Join-Path $buildRoot '.tools\avd'
    $env:GRADLE_USER_HOME = Join-Path $buildRoot '.tools\gradle-home'
    $env:TEMP = Join-Path $buildRoot '.tools\tmp'
    $env:TMP = $env:TEMP
    New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME,$env:ANDROID_AVD_HOME,$env:TEMP,(Join-Path $projectRoot '.tools\logs'),(Join-Path $projectRoot 'releases') | Out-Null
    $sdkProperty = $env:ANDROID_HOME.Replace('\','/').Replace(':','\:')
    [IO.File]::WriteAllText($localProperties, "sdk.dir=$sdkProperty`n", [Text.UTF8Encoding]::new($false))
    $tasks = @(':app:assemble' + $Variant)
    if ($Test) { $tasks = @(':core:checkCore') + $tasks }
    if ($AndroidTest) { $tasks += @(':app:assemble' + $Variant + 'AndroidTest') }
    $gradle = Join-Path $buildRoot ".tools\gradle\gradle-$gradleVersion\bin\gradle.bat"
    Push-Location $buildRoot
    try {
        & $gradle @tasks --no-daemon --console=plain --max-workers=4 '-Pandroid.overridePathCheck=true' "-PimeTestBuildType=$($Variant.ToLowerInvariant())" '-Duser.language=en' '-Duser.country=US' 2>&1 | Tee-Object -FilePath (Join-Path $projectRoot '.tools\logs\build-latest.log')
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed; see .tools/logs/build-latest.log (exit $LASTEXITCODE)." }
    } finally { Pop-Location }
    $variantPath = $Variant.ToLowerInvariant()
    $builtApk = Join-Path $projectRoot "app\build\outputs\apk\$variantPath\app-$variantPath.apk"
    if (-not (Test-Path -LiteralPath $builtApk)) { throw "APK was not generated: $builtApk" }
    if ($Variant -eq 'Release') {
        & "$PSScriptRoot\release-signing.ps1" -Action Sign -Apk $builtApk -JavaHome $JavaHome
        if ($AndroidTest) {
            $testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
            & "$PSScriptRoot\release-signing.ps1" -Action Sign -Apk $testApk -JavaHome $JavaHome
        }
    }
    $buildText = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle') -Raw
    $version = [regex]::Match($buildText, "versionName\s+'([^']+)'").Groups[1].Value
    if (-not $version) { $version = 'mvp' }
    $artifactName = if ($Variant -eq 'Debug') { "Qingyu-$version-debug.apk" } else { "Qingyu-$version.apk" }
    $artifact = Join-Path $projectRoot "releases\$artifactName"
    Copy-Item -LiteralPath $builtApk -Destination $artifact -Force
    & (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat') verify --verbose $artifact
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
    [IO.File]::WriteAllText("$artifact.sha256", "$hash  $([IO.Path]::GetFileName($artifact))`n", [Text.UTF8Encoding]::new($false))
    Write-Output "APK: $artifact"
    Write-Output "SHA256: $hash"
    if ($Install) {
        & (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') install -r $artifact
        if ($LASTEXITCODE -ne 0) { throw 'ADB installation failed. Connect one Android device or emulator.' }
    }
} finally {
    if ($null -ne $savedLocal) { [IO.File]::WriteAllBytes($localProperties, $savedLocal) }
    elseif (Test-Path -LiteralPath $localProperties) { Remove-Item -LiteralPath $localProperties }
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], 'Process') }
    if ($substDrive) { & subst.exe $substDrive /D }
}
