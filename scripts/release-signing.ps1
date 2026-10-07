param(
    [ValidateSet('Initialize', 'Check', 'Sign', 'Backup', 'Restore')][string]$Action = 'Check',
    [string]$Apk,
    [string]$BackupFile,
    [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$signRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$signDirectory = Join-Path $signRoot 'release-signing'
$signProperties = Join-Path $signDirectory 'signing.properties'
$signPolicy = Join-Path $signRoot 'docs\release-signing-policy.json'
. "$PSScriptRoot\resolve-java.ps1"
$JavaHome = Resolve-QingyuJavaHome -Requested $JavaHome
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$apksigner = Join-Path $signRoot '.tools\android-sdk\build-tools\35.0.0\apksigner.bat'
$signUtf8 = [Text.UTF8Encoding]::new($false)
$signSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$allowedSids = @($signSid.Value, 'S-1-5-18', 'S-1-5-32-544')

function Protect-SigningDirectory([string]$Path) {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($signSid)
    foreach ($sid in $allowedSids) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid), 'FullControl',
            'ContainerInherit, ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Invoke-SigningTool([string]$Tool, [string[]]$Arguments) {
    $output = & $Tool @Arguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'Signing tool failed. Check the protected signing files; no credentials were logged.' }
    return $output
}

function Get-SigningCertificate([string]$Store, [string]$Alias) {
    $output = Invoke-SigningTool $keytool @('-list', '-v', '-keystore', $Store,
        '-alias', $Alias, '-storepass:env', 'QINGYU_RELEASE_PASSWORD', '-J-Duser.language=en')
    $match = [regex]::Match($output, 'SHA256:\s*([0-9A-F:]+)')
    if (-not $match.Success) { throw 'Signing certificate fingerprint is unavailable.' }
    return $match.Groups[1].Value.Replace(':', '').ToLowerInvariant()
}

function Read-SigningProperties {
    if (-not (Test-Path -LiteralPath $signProperties)) { throw 'Release signing is missing. Restore it or initialize it once; builds never create a replacement key.' }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($signProperties)) {
        if ($line -match '^([^#=]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    foreach ($name in @('storePassword', 'keyPassword', 'keyAlias', 'legacyAlias')) {
        if (-not $values[$name]) { throw 'Release signing properties are incomplete.' }
    }
    if ($values['storePassword'] -ne $values['keyPassword']) { throw 'PKCS12 store and key passwords must match.' }
    return $values
}

function Open-SigningBackup([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (-not (Test-Path -LiteralPath "$Path.sha256")) { throw 'The backup SHA-256 sidecar is missing.' }
    $expectedHash = ([IO.File]::ReadAllText("$Path.sha256").Trim() -split '\s+')[0]
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $expectedHash) { throw 'Signing backup checksum mismatch.' }
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    $expected = @('qingyu-release.p12', 'qingyu-legacy.p12', 'qingyu.lineage', 'signing.properties', 'release-signing-policy.json')
    if ($archive.Entries.Count -ne $expected.Count -or @($archive.Entries.FullName | Select-Object -Unique).Count -ne $expected.Count -or
        @($archive.Entries | Where-Object { $_.FullName -notin $expected }).Count -ne 0) {
        $archive.Dispose()
        throw 'Signing backup has unexpected paths. Extraction was refused.'
    }
    return $archive
}

$savedSignEnv = @{}
foreach ($name in @('JAVA_HOME', 'QINGYU_RELEASE_PASSWORD', 'QINGYU_LEGACY_PASSWORD')) {
    $savedSignEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:JAVA_HOME = $JavaHome
    if ($Action -eq 'Restore') {
        if (-not $BackupFile) { throw 'Restore requires -BackupFile and its .sha256 sidecar.' }
        if (Test-Path -LiteralPath $signDirectory) { throw 'release-signing/ already exists. Restore never overwrites signing identities.' }
        $archive = Open-SigningBackup ([IO.Path]::GetFullPath($BackupFile))
        try {
            New-Item -ItemType Directory -Path $signDirectory | Out-Null
            Protect-SigningDirectory $signDirectory
            foreach ($entry in $archive.Entries) {
                $target = if ($entry.FullName -eq 'release-signing-policy.json') { $signPolicy } else { Join-Path $signDirectory $entry.FullName }
                $input = $entry.Open()
                try {
                    if ($entry.FullName -eq 'release-signing-policy.json' -and (Test-Path -LiteralPath $target)) {
                        $reader = [IO.StreamReader]::new($input)
                        try { $backedPolicy = $reader.ReadToEnd() } finally { $reader.Dispose() }
                        if ($backedPolicy.Trim() -ne [IO.File]::ReadAllText($target).Trim()) { throw 'Backup identity differs from the repository signing policy.' }
                    } else {
                        $output = [IO.File]::Open($target, 'CreateNew', 'Write', 'None')
                        try { $input.CopyTo($output) } finally { $output.Dispose() }
                    }
                } finally { $input.Dispose() }
            }
        } finally { $archive.Dispose() }
    }
    if ($Action -eq 'Initialize') {
        if (Test-Path -LiteralPath $signDirectory) { throw 'release-signing/ already exists. It will not be replaced; restore and run Check.' }
        if (Test-Path -LiteralPath $signPolicy) { throw 'A public signing policy already exists. Restore its matching private material instead of creating a new identity.' }
        $legacyDebug = Join-Path $signRoot '.tools\android-user\debug.keystore'
        if (-not (Test-Path -LiteralPath $legacyDebug)) { throw 'The original v0.4 signing key is required for a safe rotation.' }
        New-Item -ItemType Directory -Path $signDirectory | Out-Null
        Protect-SigningDirectory $signDirectory
        $randomBytes = [byte[]]::new(48)
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
        $env:QINGYU_RELEASE_PASSWORD = [Convert]::ToBase64String($randomBytes)
        $env:QINGYU_LEGACY_PASSWORD = 'android'
        $null = Invoke-SigningTool $keytool @('-genkeypair', '-keystore', (Join-Path $signDirectory 'qingyu-release.p12'),
            '-storetype', 'PKCS12', '-alias', 'qingyu-release', '-keyalg', 'RSA', '-keysize', '4096',
            '-sigalg', 'SHA256withRSA', '-validity', '36500', '-dname', 'CN=Qingyu Release, O=Qingyu',
            '-storepass:env', 'QINGYU_RELEASE_PASSWORD', '-keypass:env', 'QINGYU_RELEASE_PASSWORD')
        $null = Invoke-SigningTool $keytool @('-importkeystore', '-noprompt', '-srckeystore', $legacyDebug,
            '-srcalias', 'androiddebugkey', '-srcstorepass:env', 'QINGYU_LEGACY_PASSWORD',
            '-srckeypass:env', 'QINGYU_LEGACY_PASSWORD', '-destkeystore', (Join-Path $signDirectory 'qingyu-legacy.p12'),
            '-deststoretype', 'PKCS12', '-destalias', 'qingyu-legacy',
            '-deststorepass:env', 'QINGYU_RELEASE_PASSWORD', '-destkeypass:env', 'QINGYU_RELEASE_PASSWORD')
        $secretText = "keyAlias=qingyu-release`nlegacyAlias=qingyu-legacy`nstorePassword=$env:QINGYU_RELEASE_PASSWORD`nkeyPassword=$env:QINGYU_RELEASE_PASSWORD`n"
        [IO.File]::WriteAllText($signProperties, $secretText, $signUtf8)
        $null = Invoke-SigningTool $apksigner @('rotate', '--out', (Join-Path $signDirectory 'qingyu.lineage'),
            '--old-signer', '--ks', (Join-Path $signDirectory 'qingyu-legacy.p12'), '--ks-key-alias', 'qingyu-legacy',
            '--ks-pass', 'env:QINGYU_RELEASE_PASSWORD', '--key-pass', 'env:QINGYU_RELEASE_PASSWORD',
            '--set-installed-data', 'true', '--set-rollback', 'false',
            '--new-signer', '--ks', (Join-Path $signDirectory 'qingyu-release.p12'), '--ks-key-alias', 'qingyu-release',
            '--ks-pass', 'env:QINGYU_RELEASE_PASSWORD', '--key-pass', 'env:QINGYU_RELEASE_PASSWORD')
        $legacyCert = Get-SigningCertificate (Join-Path $signDirectory 'qingyu-legacy.p12') 'qingyu-legacy'
        if ($legacyCert -ne '12794d0f0be3a864281e828ad389f8e71bb725e196cb4bf65c4937069452d6d9') {
            throw 'The compatibility key does not match the original v0.4 certificate.'
        }
        $policy = [ordered]@{
            application_id = 'com.qingyu.ime'
            release_certificate_sha256 = (Get-SigningCertificate (Join-Path $signDirectory 'qingyu-release.p12') 'qingyu-release')
            legacy_certificate_sha256 = $legacyCert
            rotation_min_sdk = 28
            legacy_max_sdk = 27
            lineage_sha256 = (Get-FileHash -LiteralPath (Join-Path $signDirectory 'qingyu.lineage') -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        [IO.File]::WriteAllText($signPolicy, ($policy | ConvertTo-Json) + "`n", $signUtf8)
    }

    $credentials = Read-SigningProperties
    $env:QINGYU_RELEASE_PASSWORD = $credentials['storePassword']
    $policy = Get-Content -LiteralPath $signPolicy -Raw | ConvertFrom-Json
    foreach ($file in @(Get-Item -LiteralPath $signDirectory) + @(Get-ChildItem -LiteralPath $signDirectory -Recurse)) {
        $acl = Get-Acl -LiteralPath $file.FullName
        foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin $allowedSids) {
                throw 'Signing material permits another account. Restrict its ACL before building.'
            }
        }
    }
    $releaseStore = Join-Path $signDirectory 'qingyu-release.p12'
    $legacyStore = Join-Path $signDirectory 'qingyu-legacy.p12'
    $lineage = Join-Path $signDirectory 'qingyu.lineage'
    if ((Get-SigningCertificate $releaseStore $credentials['keyAlias']) -ne $policy.release_certificate_sha256 -or
        (Get-SigningCertificate $legacyStore $credentials['legacyAlias']) -ne $policy.legacy_certificate_sha256 -or
        (Get-FileHash -LiteralPath $lineage -Algorithm SHA256).Hash.ToLowerInvariant() -ne $policy.lineage_sha256) {
        throw 'Signing identity does not match docs/release-signing-policy.json.'
    }
    if ($policy.rotation_min_sdk -ne 28 -or $policy.legacy_max_sdk -ne 27 -or
        $policy.release_certificate_sha256 -eq $policy.legacy_certificate_sha256) {
        throw 'Signing rotation policy is invalid.'
    }
    $lineageOutput = Invoke-SigningTool $apksigner @('lineage', '--in', $lineage, '--print-certs', '-v')
    if (-not $lineageOutput.Contains($policy.release_certificate_sha256) -or -not $lineageOutput.Contains($policy.legacy_certificate_sha256)) {
        throw 'Signing lineage does not contain both expected certificates.'
    }
    if ([regex]::Matches($lineageOutput, 'Has installed data capability:\s*true').Count -ne 2 -or
        [regex]::Matches($lineageOutput, 'Has rollback capability\s*:\s*false').Count -ne 2) {
        throw 'Signing lineage must preserve installed data and reject rollback for both identities.'
    }

    if ($Action -in @('Initialize', 'Backup')) {
        $backupDirectory = Join-Path $signDirectory 'backups'
        New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
        $backup = Join-Path $backupDirectory ("Qingyu-signing-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
        if (Test-Path -LiteralPath $backup) { throw 'Backup already exists; it will not be replaced.' }
        Compress-Archive -LiteralPath $releaseStore, $legacyStore, $lineage, $signProperties, $signPolicy -DestinationPath $backup
        $backupHash = (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
        [IO.File]::WriteAllText("$backup.sha256", "$backupHash  $([IO.Path]::GetFileName($backup))`n", $signUtf8)
        Write-Output "Protected signing backup: $backup (contains private keys and credentials; keep offline and encrypted)."
    }
    $latestBackup = Get-ChildItem -LiteralPath (Join-Path $signDirectory 'backups') -Filter '*.zip' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($latestBackup) {
        $archive = Open-SigningBackup $latestBackup.FullName
        try {
            foreach ($entry in $archive.Entries) {
                $source = if ($entry.FullName -eq 'release-signing-policy.json') { $signPolicy } else { Join-Path $signDirectory $entry.FullName }
                $stream = $entry.Open()
                $hasher = [Security.Cryptography.SHA256]::Create()
                try { $entryHash = ([BitConverter]::ToString($hasher.ComputeHash($stream))).Replace('-', '') }
                finally { $stream.Dispose(); $hasher.Dispose() }
                if ($entryHash -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) { throw 'Signing backup does not match current material.' }
            }
        } finally { $archive.Dispose() }
    }
    if ($Action -eq 'Sign') {
        if (-not $Apk -or -not (Test-Path -LiteralPath $Apk)) { throw 'Specify a newly built APK with -Apk.' }
        $Apk = [IO.Path]::GetFullPath($Apk)
        $allowedBuild = [IO.Path]::GetFullPath((Join-Path $signRoot 'app\build\outputs\apk')) + [IO.Path]::DirectorySeparatorChar
        if (-not $Apk.StartsWith($allowedBuild, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Only app/build/outputs/apk/ can be signed. Published and older release APKs are immutable.'
        }
        $signed = "$Apk.rotated.apk"
        try {
            $null = Invoke-SigningTool $apksigner @('sign', '--in', $Apk, '--out', $signed,
                '--min-sdk-version', '26', '--rotation-min-sdk-version', '28', '--lineage', $lineage,
                '--v4-signing-enabled', 'false',
                '--ks', $legacyStore, '--ks-key-alias', $credentials['legacyAlias'],
                '--ks-pass', 'env:QINGYU_RELEASE_PASSWORD', '--key-pass', 'env:QINGYU_RELEASE_PASSWORD',
                '--next-signer', '--ks', $releaseStore, '--ks-key-alias', $credentials['keyAlias'],
                '--ks-pass', 'env:QINGYU_RELEASE_PASSWORD', '--key-pass', 'env:QINGYU_RELEASE_PASSWORD')
            $null = Invoke-SigningTool $apksigner @('verify', '--verbose', $signed)
            foreach ($range in @(@(26, 27, $policy.legacy_certificate_sha256), @(28, 35, $policy.release_certificate_sha256))) {
                $certs = Invoke-SigningTool $apksigner @('verify', '--print-certs', '--min-sdk-version', [string]$range[0], '--max-sdk-version', [string]$range[1], $signed)
                if (-not $certs.Contains([string]$range[2])) { throw 'APK did not use the expected platform-specific signing certificate.' }
            }
            Move-Item -LiteralPath $signed -Destination $Apk -Force
        } finally {
            if (Test-Path -LiteralPath $signed) { Remove-Item -LiteralPath $signed }
        }
    }
    Write-Output 'RELEASE_SIGNING_CHECKS_PASS (identity, lineage, private ACL; no credentials printed).'
    Write-Output "Release certificate SHA-256: $($policy.release_certificate_sha256)"
} finally {
    foreach ($name in $savedSignEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $savedSignEnv[$name], 'Process') }
}
