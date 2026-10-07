[CmdletBinding()]
param([string]$Serial='emulator-5554',[string]$JavaHome)
$ErrorActionPreference='Stop'
$upgradeRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $upgradeRoot 'scripts/resolve-java.ps1')
$JavaHome=Resolve-QingyuJavaHome -Requested $JavaHome
$upgradeSdk=Join-Path $upgradeRoot '.tools/android-sdk'
$upgradeAdb=Join-Path $upgradeSdk 'platform-tools/adb.exe'
$upgradeOutput=Join-Path $upgradeRoot '.tools/chinese-upgrade'
$upgradeClasses=Join-Path $upgradeOutput 'classes'
New-Item -ItemType Directory -Force -Path $upgradeClasses | Out-Null
$upgradeSources=@(Get-ChildItem -LiteralPath (Join-Path $upgradeRoot 'core/src/main/java/com/qingyu/core') -Filter '*.java' -File | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -source 17 -target 17 -d $upgradeClasses @upgradeSources (Join-Path $PSScriptRoot 'ChineseUpgradeSmoke.java')
if($LASTEXITCODE -ne 0){throw 'Chinese upgrade Java compilation failed'}
$upgradeClassFiles=@(Get-ChildItem -LiteralPath $upgradeClasses -Filter '*.class' -Recurse -File | ForEach-Object FullName)
$upgradeJar=Join-Path $upgradeOutput 'checks.jar'
& (Join-Path $JavaHome 'bin/java.exe') -cp (Join-Path $upgradeSdk 'build-tools/35.0.0/lib/d8.jar') com.android.tools.r8.D8 --min-api 26 --lib (Join-Path $upgradeSdk 'platforms/android-35/android.jar') --output $upgradeJar @upgradeClassFiles
if($LASTEXITCODE -ne 0){throw 'Chinese upgrade DEX compilation failed'}
function Invoke-UpgradeAdb([string[]]$Arguments){$upgradeResult=& $upgradeAdb -s $Serial @Arguments;if($LASTEXITCODE -ne 0){throw "Chinese upgrade adb failed: $($Arguments -join ' ')"};return $upgradeResult}
$upgradeRemote='/data/local/tmp/qingyu-upgrade'
Invoke-UpgradeAdb @('shell','mkdir','-p',$upgradeRemote) | Out-Null
Invoke-UpgradeAdb @('push',$upgradeJar,"$upgradeRemote/checks.jar") | Out-Null
Invoke-UpgradeAdb @('push',(Join-Path $upgradeRoot '.tools/pinyin-model/dict-v0.4.dat'),"$upgradeRemote/old.dat") | Out-Null
Invoke-UpgradeAdb @('push',(Join-Path $upgradeRoot 'app/src/main/assets/pinyin/dict_pinyin.dat'),"$upgradeRemote/new.dat") | Out-Null
Invoke-UpgradeAdb @('push',(Join-Path $upgradeRoot '.tools/engine-build/direct/x86_64/libqingyu_pinyin.so'),"$upgradeRemote/libqingyu_pinyin.so") | Out-Null
$upgradeCommand="CLASSPATH=$upgradeRemote/checks.jar app_process -Djava.library.path=$upgradeRemote /system/bin com.qingyu.core.ChineseUpgradeSmoke $upgradeRemote/old.dat $upgradeRemote/new.dat $upgradeRemote/learned.dat"
$upgradeResult=& $upgradeAdb -s $Serial shell $upgradeCommand
$upgradeExit=$LASTEXITCODE
$upgradeResult | Set-Content -LiteralPath (Join-Path $upgradeRoot 'docs/chinese-upgrade-results.txt') -Encoding utf8
$upgradeResult
if($upgradeExit -ne 0 -or ($upgradeResult -join "`n") -notmatch 'ALL_CHINESE_UPGRADE_CHECKS_PASS'){throw 'Chinese upgrade checks failed; full report saved'}
