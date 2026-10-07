[CmdletBinding()]
param([string]$JavaHome)
$ErrorActionPreference='Stop'
$correctionRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $correctionRoot 'scripts/resolve-java.ps1')
$JavaHome=Resolve-QingyuJavaHome -Requested $JavaHome
$correctionClasses=Join-Path $correctionRoot '.tools/correctionchecks/classes'
New-Item -ItemType Directory -Force -Path $correctionClasses | Out-Null
$correctionSources=@(Get-ChildItem -LiteralPath (Join-Path $correctionRoot 'core/src/main/java/com/qingyu/core') -Filter '*.java' -File | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -source 17 -target 17 -d $correctionClasses @correctionSources (Join-Path $PSScriptRoot 'ChineseCorrectionChecks.java')
if($LASTEXITCODE -ne 0){throw 'Chinese correction check compilation failed'}
$correctionSource=Join-Path $correctionRoot '.cache/pinyin-v2/rime-ice-base.dict.yaml'
if(!(Test-Path -LiteralPath $correctionSource)){throw 'Run tools/build_pinyin_dictionary.py to prepare the pinned source lexicon first'}
$correctionDigest=(Get-FileHash -LiteralPath $correctionSource -Algorithm SHA256).Hash
if($correctionDigest -ne '0418D5103D8E40C8FFF4B686A873AD03E9836CD0419ECAE0B7E1E2526312DBA0'){throw 'Pinned correction source SHA-256 does not match'}
$correctionResult=& (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' -Xmx512m -cp $correctionClasses com.qingyu.core.ChineseCorrectionChecks $correctionSource
if($LASTEXITCODE -ne 0){throw 'Chinese correction assertions failed'}
@("Source SHA-256: $correctionDigest",$correctionResult) | Set-Content -LiteralPath (Join-Path $correctionRoot 'docs/chinese-correction-v0.5.txt') -Encoding utf8
$correctionResult
