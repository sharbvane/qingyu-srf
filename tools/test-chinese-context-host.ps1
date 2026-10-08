param([string]$JavaHome)
$ErrorActionPreference='Stop'
$contextRoot=Split-Path -Parent $PSScriptRoot
. (Join-Path $contextRoot 'scripts/resolve-java.ps1')
$JavaHome=Resolve-QingyuJavaHome -Requested $JavaHome
$contextClasses=Join-Path $contextRoot '.tools/context-check-classes'
New-Item -ItemType Directory -Force -Path $contextClasses | Out-Null
& python (Join-Path $PSScriptRoot 'build_chinese_context_checks.py')
if($LASTEXITCODE -ne 0){throw 'Context fixture builder failed'}
$contextSources=@(Get-ChildItem -LiteralPath (Join-Path $contextRoot 'core/src/main/java/com/qingyu/core') -File -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -source 17 -target 17 -d $contextClasses @contextSources (Join-Path $PSScriptRoot 'ChineseContextChecks.java') (Join-Path $PSScriptRoot 'ChineseContextModelChecks.java')
if($LASTEXITCODE -ne 0){throw 'Context host compilation failed'}
$contextSourceResult=& (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $contextClasses ChineseContextModelChecks (Join-Path $contextRoot 'app/src/main/assets/input/chinese-context-v1.bin.gz') (Join-Path $contextRoot '.cache/chinese-context-v1/zh_gsdsimp-ud-train.conllu')
if($LASTEXITCODE -ne 0){throw 'Context source checks failed'}
$contextSourceResult | Set-Content -LiteralPath (Join-Path $contextRoot 'docs/chinese-context-source-results.txt') -Encoding utf8
$contextSourceResult
$contextArguments=@('-Dfile.encoding=UTF-8','-cp',$contextClasses,'ChineseContextChecks',(Join-Path $contextRoot 'app/src/main/assets/input/chinese-context-v1.bin.gz'),(Join-Path $contextRoot '.tools/chinese-context-edges.tsv'),(Join-Path $PSScriptRoot 'chinese-context-heldout.tsv'))
foreach($contextMode in @('baseline','current')){
    $contextRun=$contextArguments
    if($contextMode -eq 'baseline'){$contextRun+=@('baseline')}
    $contextResult=& (Join-Path $JavaHome 'bin/java.exe') @contextRun
    if($LASTEXITCODE -ne 0){throw 'Context host checks failed'}
    $contextName=if($contextMode -eq 'baseline'){'chinese-context-host-baseline-v0.5.txt'}else{'chinese-context-host-v0.6.txt'}
    $contextResult | Set-Content -LiteralPath (Join-Path $contextRoot "docs/$contextName") -Encoding utf8
    $contextResult | Select-Object -Last 1
}
