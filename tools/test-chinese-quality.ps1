[CmdletBinding()]
param([string]$Serial='emulator-5554',[string]$Dictionary='', [switch]$Baseline, [switch]$Acceptance, [switch]$SkipNativeBuild, [string]$JavaHome)
$ErrorActionPreference='Stop'
$qualityRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $qualityRoot 'scripts/resolve-java.ps1')
$JavaHome=Resolve-QingyuJavaHome -Requested $JavaHome
$qualitySdk=Join-Path $qualityRoot '.tools/android-sdk'
$qualityAdb=Join-Path $qualitySdk 'platform-tools/adb.exe'
$qualityOutput=Join-Path $qualityRoot '.tools/chinese-quality'
$qualityClasses=Join-Path $qualityOutput 'classes'
New-Item -ItemType Directory -Force -Path $qualityClasses | Out-Null
if(!$Dictionary){$Dictionary=Join-Path $qualityRoot $(if($Baseline){'.tools/pinyin-model/dict-v0.4.dat'}else{'app/src/main/assets/pinyin/dict_pinyin.dat'})}
if($Baseline -and (Get-FileHash -LiteralPath $Dictionary -Algorithm SHA256).Hash -ne '6BF0BBDE4E3134CCE38D08524A9F4DC1AF40435243C8E36B38EB68D1E14462B2'){throw 'v0.4 baseline requires the original pinned 6bf0bbde dictionary; refusing to mislabel a rebuilt model'}
if(!$SkipNativeBuild){& (Join-Path $PSScriptRoot 'build-native.ps1') -Abis @('x86_64')}
$qualitySources=@(Get-ChildItem -LiteralPath (Join-Path $qualityRoot 'core/src/main/java/com/qingyu/core') -Filter '*.java' -File | ForEach-Object FullName)
$qualitySources+=(Join-Path $qualityRoot 'app/src/main/java/com/qingyu/ime/LocalInputDictionary.java')
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -source 17 -target 17 -cp (Join-Path $qualitySdk 'platforms/android-35/android.jar') -d $qualityClasses @qualitySources (Join-Path $PSScriptRoot 'ChineseQualitySmoke.java')
if($LASTEXITCODE -ne 0){throw 'Chinese quality Java compilation failed'}
$qualityClassFiles=@(Get-ChildItem -LiteralPath $qualityClasses -Filter '*.class' -Recurse -File | ForEach-Object FullName)
$qualityJar=Join-Path $qualityOutput 'checks.jar'
& (Join-Path $JavaHome 'bin/java.exe') -cp (Join-Path $qualitySdk 'build-tools/35.0.0/lib/d8.jar') com.android.tools.r8.D8 --min-api 26 --lib (Join-Path $qualitySdk 'platforms/android-35/android.jar') --output $qualityJar @qualityClassFiles
if($LASTEXITCODE -ne 0){throw 'Chinese quality DEX compilation failed'}
function Invoke-QualityAdb([string[]]$Arguments){$qualityResult=& $qualityAdb -s $Serial @Arguments;if($LASTEXITCODE -ne 0){throw "Chinese quality adb failed: $($Arguments -join ' ')"};return $qualityResult}
$qualityRemote='/data/local/tmp/qingyu-quality'
Invoke-QualityAdb @('shell','mkdir','-p',$qualityRemote) | Out-Null
Invoke-QualityAdb @('push',$qualityJar,"$qualityRemote/checks.jar") | Out-Null
Invoke-QualityAdb @('push',$Dictionary,"$qualityRemote/dict.dat") | Out-Null
Invoke-QualityAdb @('push',(Join-Path $qualityRoot '.tools/engine-build/direct/x86_64/libqingyu_pinyin.so'),"$qualityRemote/libqingyu_pinyin.so") | Out-Null
Invoke-QualityAdb @('push',(Join-Path $PSScriptRoot 'chinese-quality.tsv'),"$qualityRemote/corpus.tsv") | Out-Null
Invoke-QualityAdb @('push',(Join-Path $qualityRoot 'app/src/main/assets/pinyin/lexicon-v2.db'),"$qualityRemote/lexicon-v2.db") | Out-Null
$qualityMode=if($Baseline){'baseline'}elseif($Acceptance){'acceptance'}else{'verify'}
$qualityUser="$qualityRemote/$qualityMode-user.dat"
$qualityCommand="CLASSPATH=$qualityRemote/checks.jar app_process -Djava.library.path=$qualityRemote /system/bin com.qingyu.core.ChineseQualitySmoke $qualityRemote/dict.dat $qualityUser $qualityRemote/corpus.tsv $qualityMode $qualityRemote/lexicon-v2.db"
$qualityResult=& $qualityAdb -s $Serial shell $qualityCommand
$qualityExit=$LASTEXITCODE
$qualityName=if($Baseline){'chinese-quality-baseline-v0.4.txt'}else{'chinese-quality-v0.5.txt'}
@("Dictionary SHA-256: $((Get-FileHash -LiteralPath $Dictionary -Algorithm SHA256).Hash)",$qualityResult) | Set-Content -LiteralPath (Join-Path $qualityRoot "docs/$qualityName") -Encoding utf8
$qualityResult
if($qualityExit -ne 0){throw "Chinese quality assertion failed ($qualityExit); full CASE results saved in docs/$qualityName; Android uncaught assertion is also in logcat."}
