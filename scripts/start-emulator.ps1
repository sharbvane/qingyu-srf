param([switch]$Visible, [string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
. "$PSScriptRoot\resolve-java.ps1"
$JavaHome = Resolve-QingyuJavaHome -Requested $JavaHome
if (-not (Test-Path -LiteralPath "$projectRoot\.tools\android-sdk\emulator\emulator.exe")) { throw 'Run scripts/setup-toolchain.ps1 -Emulator first.' }
$attached=@(& "$projectRoot\.tools\android-sdk\platform-tools\adb.exe" devices)
if($attached | Where-Object {$_ -match '^emulator-5554\s+(device|offline)'}) { throw 'An emulator already uses port 5554. Use it, or stop it before starting another.' }
$runtimeRoot = $projectRoot
$mapping = $null
$createdMapping = $false
if ($projectRoot -match '[^\x00-\x7F]') {
    # Avoid parsing subst.exe's OEM-encoded output for Chinese paths. A unique
    # temporary file proves whether an existing drive resolves to this workspace.
    $probeName='emulator-map-'+[Guid]::NewGuid().ToString('N')+'.tmp'
    $probePath=Join-Path $projectRoot ".tools\$probeName"
    [IO.File]::WriteAllText($probePath, 'workspace')
    try {
        foreach ($letter in @('Y','X','Z','W')) {
            if (Test-Path -LiteralPath "${letter}:\.tools\$probeName") { $mapping="${letter}:"; $runtimeRoot="$mapping\"; break }
            if (-not (Test-Path "${letter}:\")) {
                & subst.exe "${letter}:" $projectRoot
                if ($LASTEXITCODE -ne 0) { throw 'Could not map the emulator workspace.' }
                $createdMapping=$true
                $mapping="${letter}:"; $runtimeRoot="$mapping\"; break
            }
        }
    } finally { Remove-Item -LiteralPath $probePath }
    if (-not $mapping) { throw 'No drive letter is available for the emulator.' }
}
$names=@('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','ANDROID_USER_HOME','ANDROID_EMULATOR_HOME','ANDROID_AVD_HOME','TEMP','TMP')
$saved=@{}
foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$started=$false
try {
    $env:JAVA_HOME=$JavaHome
    $env:ANDROID_HOME=Join-Path $runtimeRoot '.tools\android-sdk'
    $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
    $env:ANDROID_USER_HOME=Join-Path $runtimeRoot '.tools\android-user'
    $env:ANDROID_EMULATOR_HOME=$env:ANDROID_USER_HOME
    $env:ANDROID_AVD_HOME=Join-Path $runtimeRoot '.tools\avd'
    $env:TEMP=Join-Path $runtimeRoot '.tools\tmp'; $env:TMP=$env:TEMP
    New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME,$env:TEMP,(Join-Path $runtimeRoot '.tools\logs') | Out-Null
    if (-not (Test-Path -LiteralPath "$env:ANDROID_AVD_HOME\Qingyu_API35.avd\config.ini")) {
        'no' | & "$env:ANDROID_HOME\cmdline-tools\12.0\bin\avdmanager.bat" create avd --force -n Qingyu_API35 -k 'system-images;android-35;google_apis;x86_64' --device 'pixel_5'
        if ($LASTEXITCODE -ne 0) { throw 'AVD creation failed.' }
    }
    $avdPath=Join-Path $env:ANDROID_AVD_HOME 'Qingyu_API35.avd'
    [IO.File]::WriteAllText("$env:ANDROID_AVD_HOME\Qingyu_API35.ini","avd.ini.encoding=UTF-8`npath=$avdPath`ntarget=android-35`n",[Text.UTF8Encoding]::new($false))
    $emulatorArgs=@('-avd','Qingyu_API35','-no-audio','-no-boot-anim','-gpu','software','-no-snapshot','-memory','2048','-cores','2','-port','5554')
    if (-not $Visible) { $emulatorArgs += '-no-window' }
    $windowStyle=if($Visible){'Normal'}else{'Hidden'}
    $process=Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList $emulatorArgs -WindowStyle $windowStyle -PassThru -RedirectStandardOutput "$runtimeRoot\.tools\logs\emulator.log" -RedirectStandardError "$runtimeRoot\.tools\logs\emulator-error.log"
    $started=$true
    Set-Content -LiteralPath "$projectRoot\.tools\emulator-drive.txt" -Value $mapping -Encoding ascii
    Write-Output "Emulator started (PID $($process.Id)). Wait until adb -s emulator-5554 shell getprop sys.boot_completed returns 1."
    Write-Output "Stop: adb -s emulator-5554 emu kill; remove the project mapping $mapping after shutdown."
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    if(-not $started -and $createdMapping){& subst.exe $mapping /D}
}
