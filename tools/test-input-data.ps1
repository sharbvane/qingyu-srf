param([string]$Serial='emulator-5554',[string]$JavaHome,[string]$Abi='x86_64')
$ErrorActionPreference='Stop'
$inputProjectRoot=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
. (Join-Path $inputProjectRoot 'scripts/resolve-java.ps1')
$JavaHome=Resolve-QingyuJavaHome -Requested $JavaHome
$inputSdk=Join-Path $inputProjectRoot '.tools/android-sdk'
$inputClasses=Join-Path $inputProjectRoot '.tools/input-check-classes'
$inputJar=Join-Path $inputProjectRoot '.tools/input-checks.jar'
$inputAdb=Join-Path $inputSdk 'platform-tools/adb.exe'
$inputAndroidJar=Join-Path $inputSdk 'platforms/android-35/android.jar'
New-Item -ItemType Directory -Force -Path $inputClasses | Out-Null
$inputSources=@('core/src/main/java/com/qingyu/core/EnglishEngine.java',
    'core/src/main/java/com/qingyu/core/NineKeyCandidate.java',
    'core/src/main/java/com/qingyu/core/ChineseEngine.java',
    'core/src/main/java/com/qingyu/core/PinyinEngine.java',
    'core/src/main/java/com/qingyu/core/ChineseCorrection.java',
    'core/src/main/java/com/qingyu/core/ChineseContextModel.java',
    'core/src/main/java/com/qingyu/core/NativeDecoder.java',
    'core/src/main/java/com/qingyu/core/Candidate.java',
    'core/src/main/java/com/qingyu/core/EngineSnapshot.java',
    'app/src/main/java/com/qingyu/ime/LocalInputDictionary.java',
    'tools/EnglishEngineSmoke.java','tools/InputDictionarySmoke.java') |
    ForEach-Object {Join-Path $inputProjectRoot $_}
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $inputAndroidJar -d $inputClasses @inputSources
if($LASTEXITCODE -ne 0) {throw 'Input data check compile failed'}
$englishResult=& (Join-Path $JavaHome 'bin/java.exe') -cp $inputClasses EnglishEngineSmoke (Join-Path $inputProjectRoot 'app/src/main/assets/input')
if($LASTEXITCODE -ne 0) {throw 'English engine check failed'}
$englishResult
$inputClassFiles=@(Get-ChildItem -LiteralPath $inputClasses -Filter '*.class' -Recurse -File | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin/java.exe') -cp (Join-Path $inputSdk 'build-tools/35.0.0/lib/d8.jar') com.android.tools.r8.D8 --min-api 26 --lib $inputAndroidJar --output $inputJar @inputClassFiles
if($LASTEXITCODE -ne 0) {throw 'Input data DEX build failed'}
function Invoke-InputAdb([string[]]$Arguments) {
    $result=& $inputAdb -s $Serial @Arguments
    if($LASTEXITCODE -ne 0) {throw "Input data adb failed: $($Arguments -join ' ')"}
    return $result
}
Invoke-InputAdb @('shell','mkdir','-p','/data/local/tmp/qingyu-engine') | Out-Null
Invoke-InputAdb @('push',$inputJar,'/data/local/tmp/qingyu-engine/input-checks.jar')
Invoke-InputAdb @('push',(Join-Path $inputProjectRoot 'app/src/main/assets/input/input-v3.db'),'/data/local/tmp/qingyu-engine/input-v3.db')
Invoke-InputAdb @('push',(Join-Path $inputProjectRoot 'app/src/main/assets/pinyin/lexicon-v2.db'),'/data/local/tmp/qingyu-engine/lexicon-v2.db')
Invoke-InputAdb @('push',(Join-Path $inputProjectRoot 'app/src/main/assets/pinyin/dict_pinyin.dat'),'/data/local/tmp/qingyu-engine/input-dict.dat')
Invoke-InputAdb @('push',(Join-Path $inputProjectRoot 'app/src/main/assets/input/chinese-context-v1.bin.gz'),'/data/local/tmp/qingyu-engine/context.bin.gz')
Invoke-InputAdb @('push',(Join-Path $inputProjectRoot ".tools/engine-build/direct/$Abi/libqingyu_pinyin.so"),'/data/local/tmp/qingyu-engine/libqingyu_pinyin.so')
$sqliteResult=Invoke-InputAdb @('shell','CLASSPATH=/data/local/tmp/qingyu-engine/input-checks.jar app_process -Djava.library.path=/data/local/tmp/qingyu-engine /system/bin com.qingyu.ime.InputDictionarySmoke /data/local/tmp/qingyu-engine/input-v3.db /data/local/tmp/qingyu-engine/lexicon-v2.db /data/local/tmp/qingyu-engine/input-dict.dat /data/local/tmp/qingyu-engine/input-segment-user.dat /data/local/tmp/qingyu-engine/context.bin.gz')
$sqliteResult
if(($englishResult -join "`n") -notmatch 'ALL_ENGLISH_ENGINE_CHECKS_PASS' -or ($sqliteResult -join "`n") -notmatch 'ALL_INPUT_DICTIONARY_CHECKS_PASS') {throw 'Input data success marker missing'}
@($englishResult;$sqliteResult) | Set-Content -LiteralPath (Join-Path $inputProjectRoot 'docs/input-language-results.txt') -Encoding utf8
