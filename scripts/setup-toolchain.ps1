param([switch]$Emulator, [string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
. "$PSScriptRoot\resolve-java.ps1"
$JavaHome = Resolve-QingyuJavaHome -Requested $JavaHome
$toolRoot = Join-Path $projectRoot '.tools'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$cacheRoot = Join-Path $toolRoot 'cache'
New-Item -ItemType Directory -Force -Path $cacheRoot,$sdkRoot,(Join-Path $toolRoot 'logs') | Out-Null

function Get-CheckedArchive([string]$Name, [string]$Url, [string]$Hash, [string]$Algorithm = 'SHA1') {
    $archive = Join-Path $cacheRoot $Name
    if ((Test-Path -LiteralPath $archive) -and ((Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash -eq $Hash)) { return $archive }
    Write-Host "Downloading $Name"
    & curl.exe --ssl-revoke-best-effort --silent --show-error --location --fail --retry 3 --connect-timeout 15 $Url -o $archive
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Name" }
    if ((Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash -ne $Hash) { throw "Checksum mismatch: $Name" }
    return $archive
}
function Expand-CheckedArchive([string]$Archive, [string]$Destination) {
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    & tar.exe -xf $Archive -C $Destination
    if ($LASTEXITCODE -ne 0) { throw "Extraction failed: $Archive" }
}

# Gradle's SHA-256 was verified against downloads.gradle.org. The mirror avoids
# slow GitHub redirects in some regions; the distribution bytes must match.
if (-not (Test-Path -LiteralPath "$toolRoot\gradle\gradle-8.9\bin\gradle.bat")) {
    $archive = Get-CheckedArchive 'gradle-8.9-bin-mirror.zip' 'https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip' 'd725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab' 'SHA256'
    Expand-CheckedArchive $archive "$toolRoot\gradle"
}
$packages = @(
    @{ Name='platform-tools_r37.0.1-win.zip'; Hash='e03e78b1d80b396f1c3358e31251cb31740e1110'; Check='platform-tools\adb.exe'; Destination=''; Rename=$null },
    @{ Name='platform-35_r02.zip'; Hash='0bb560a90a7a2cbd0dd8348224d518b638fe7949'; Check='platforms\android-35\android.jar'; Destination='platforms'; Rename=$null },
    @{ Name='build-tools_r35_windows.zip'; Hash='af059bb67cf7786f45ee0db85e2d24985df1b4b6'; Check='build-tools\35.0.0\aapt2.exe'; Destination='build-tools'; Rename=@('android-15','35.0.0') },
    @{ Name='android-ndk-r28b-windows.zip'; Hash='c7d82072807fcabbd6ee356476761d8729307185'; Check='ndk\28.1.13356709\source.properties'; Destination='ndk'; Rename=@('android-ndk-r28b','28.1.13356709') },
    @{ Name='cmake-3.22.1-windows.zip'; Hash='292778f32a7d5183e1c49c7897b870653f2d2c1b'; Check='cmake\3.22.1\bin\cmake.exe'; Destination='cmake\3.22.1'; Rename=$null },
    @{ Name='commandlinetools-win-11076708_latest.zip'; Hash='3d2917302740f476999a091bc5558837c7a863c5'; Check='cmdline-tools\12.0\bin\sdkmanager.bat'; Destination='cmdline-tools'; Rename=@('cmdline-tools','12.0') }
)
if ($Emulator) {
    $packages += @{ Name='emulator-windows_x64-16433917.zip'; Hash='6ac24315017357bb8d7bfeb64ca653829a57b891'; Check='emulator\emulator.exe'; Destination=''; Rename=$null }
    $packages += @{ Name='x86_64-35_r09.zip'; Hash='0103e6dab21290c4b9d16550a3ce99476f884eef'; Check='system-images\android-35\google_apis\x86_64\system.img'; Destination='system-images\android-35\google_apis'; Rename=$null }
}
foreach ($package in $packages) {
    if (Test-Path -LiteralPath (Join-Path $sdkRoot $package.Check)) { continue }
    $baseUrl = if ($package.Name -eq 'x86_64-35_r09.zip') { 'https://dl.google.com/android/repository/sys-img/google_apis/' } else { 'https://dl.google.com/android/repository/' }
    $archive = Get-CheckedArchive $package.Name ($baseUrl + $package.Name) $package.Hash
    $destination = if ($package.Destination) { Join-Path $sdkRoot $package.Destination } else { $sdkRoot }
    Expand-CheckedArchive $archive $destination
    if ($package.Rename) { Move-Item -LiteralPath (Join-Path $destination $package.Rename[0]) -Destination (Join-Path $destination $package.Rename[1]) }
}

$savedJava = $env:JAVA_HOME
$savedUser = $env:ANDROID_USER_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
    New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
    $sdkManager = Join-Path $sdkRoot 'cmdline-tools\12.0\bin\sdkmanager.bat'
    # Building with the Android SDK implies accepting its SDK terms. Keep the
    # installer transcript locally and avoid printing every license in the chat.
    1..20 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses 2>&1 | Set-Content -LiteralPath (Join-Path $toolRoot 'logs\sdk-licenses.log') -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'SDK license setup failed; see .tools/logs/sdk-licenses.log.' }
    if ($Emulator) {
        # Repository metadata is needed for avdmanager when packages were unpacked
        # from verified archives rather than downloaded through sdkmanager.
        $metadata = @'
<?xml version="1.0" encoding="UTF-8"?><r:repository xmlns:r="http://schemas.android.com/repository/android/common/02" xmlns:g="http://schemas.android.com/repository/android/generic/02" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><localPackage path="emulator" obsolete="false"><type-details xsi:type="g:genericDetailsType"/><revision><major>37</major><minor>3</minor><micro>2</micro></revision><display-name>Android Emulator</display-name></localPackage></r:repository>
'@
        [IO.File]::WriteAllText("$sdkRoot\emulator\package.xml", $metadata, [Text.UTF8Encoding]::new($false))
        $imageMetadata = @'
<?xml version="1.0" encoding="UTF-8"?><r:repository xmlns:r="http://schemas.android.com/repository/android/common/02" xmlns:s="http://schemas.android.com/sdk/android/repo/sys-img2/03" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><localPackage path="system-images;android-35;google_apis;x86_64" obsolete="false"><type-details xsi:type="s:sysImgDetailsType"><api-level>35</api-level><tag><id>google_apis</id><display>Google APIs</display></tag><vendor><id>google</id><display>Google Inc.</display></vendor><abi>x86_64</abi></type-details><revision><major>9</major></revision><display-name>Google APIs Intel x86 Atom_64 System Image</display-name></localPackage></r:repository>
'@
        [IO.File]::WriteAllText("$sdkRoot\system-images\android-35\google_apis\x86_64\package.xml", $imageMetadata, [Text.UTF8Encoding]::new($false))
    }
} finally { $env:JAVA_HOME = $savedJava; $env:ANDROID_USER_HOME = $savedUser }
Write-Output "Toolchain ready: $toolRoot"
