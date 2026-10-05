[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$engineVersion = '05cc8f8f2465553bc6d19b2ede24073d079eeed7'
$sourceDirectory = Join-Path $projectRoot '.tools/engine-source'
New-Item -ItemType Directory -Force -Path $sourceDirectory | Out-Null
$archive = Join-Path $sourceDirectory "pinyinime-$engineVersion.zip"
$url = "https://codeload.github.com/oxangen/PinyinIME/zip/$engineVersion"
& curl.exe -fL --retry 2 --connect-timeout 15 --max-time 120 $url -o $archive
if ($LASTEXITCODE -ne 0) { throw 'AOSP mirror archive download failed' }
$archiveHash = '6cfb6b3cf7add8a35a30baab18010b1c2ebe801186150d7a72e6c6f6c6f401e1'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $archiveHash) {
    throw 'Pinned engine archive hash did not match'
}
Expand-Archive -LiteralPath $archive -DestinationPath $sourceDirectory -Force
$dictionary = Join-Path $sourceDirectory "PinyinIME-$engineVersion/res/raw/dict_pinyin.dat"
$expectedHash = '6bf0bbde4e3134cce38d08524a9f4dc1af40435243c8e36b38eb68d1e14462b2'
if ((Get-FileHash -LiteralPath $dictionary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Pinned engine dictionary hash did not match'
}
Write-Output "Verified source archive at $archive"
Write-Output 'Vendored sources are not automatically overwritten: keep the documented Qingyu privacy/ABI patches.'
