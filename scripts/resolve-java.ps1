function Resolve-QingyuJavaHome {
    param([string]$Requested)

    $candidates = [Collections.Generic.List[string]]::new()
    foreach ($value in @($Requested, $env:JAVA_HOME)) {
        if ($value) { $candidates.Add($value) }
    }

    foreach ($registryRoot in @(
        'HKLM:\SOFTWARE\JavaSoft\JDK',
        'HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK',
        'HKLM:\SOFTWARE\Eclipse Adoptium\JDK'
    )) {
        if (-not (Test-Path -LiteralPath $registryRoot)) { continue }
        $children = @(Get-ChildItem -LiteralPath $registryRoot -ErrorAction SilentlyContinue)
        foreach ($child in $children) {
            $value = Get-ItemProperty -LiteralPath $child.PSPath -ErrorAction SilentlyContinue
            if ($value.JavaHome) { $candidates.Add([string]$value.JavaHome) }
        }
    }

    foreach ($programRoot in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if (-not $programRoot) { continue }
        foreach ($folder in @(Get-ChildItem -LiteralPath $programRoot -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue)) {
            $candidates.Add($folder.FullName)
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $java = Join-Path $candidate 'bin\java.exe'
        $javac = Join-Path $candidate 'bin\javac.exe'
        if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javac)) { continue }
        $version = (& $java -version 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0 -and $version -match 'version "17(?:\.|\+)' ) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }

    throw 'JDK 17 was not found. Set JAVA_HOME or pass -JavaHome with the JDK directory.'
}
