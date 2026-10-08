param([string[]]$Abis = @('arm64-v8a', 'armeabi-v7a'))
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$buildTools = Join-Path $project 'android/tools/sdk/build-tools/35.0.0'
$artifacts = Join-Path $project 'artifacts'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$reports = @()
$certificate = $null
$secret = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY')
$inputTypes = @('LensKeyboardService', 'InputAssistService', 'InputAssistSettingsActivity', 'InputShareActivity', 'ProcessTextActivity', 'PinyinLexicon', 'StrokeLexicon', 'KeyboardInputEditor', 'InputReplacement')
foreach ($edition in @('full', 'screen')) {
    $directory = Join-Path $project "android/app/build/outputs/apk/$edition/debug"
    $metadata = Get-Content -LiteralPath (Join-Path $directory 'output-metadata.json') -Raw | ConvertFrom-Json
    foreach ($abi in $Abis) {
        if ($abi -notin @('arm64-v8a', 'armeabi-v7a', 'x86_64')) { throw 'Unsupported ABI' }
        $output = @($metadata.elements | Where-Object { $_.filters | Where-Object { $_.filterType -eq 'ABI' -and $_.value -eq $abi } })
        if ($output.Count -ne 1) { throw "Missing unique $edition / $abi output" }
        $source = Join-Path $directory $output[0].outputFile
        $version = $output[0].versionName
        if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid build version' }
        $destination = Join-Path $artifacts "Lens-Translate-$version-$edition-$abi-debug.apk"
        # Use only the final patch suffix for the artifact series (0.9.0 -> 0.9).
        $auditDirectory = Join-Path $artifacts ('android-' + ($version -replace '\.0$', '') + '/' + $edition)
        New-Item -ItemType Directory -Path $auditDirectory -Force | Out-Null
        $signature = (& (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $source) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $signature -notmatch 'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)') { throw "Signature verification failed for $edition / $abi" }
        if ($null -eq $certificate) { $certificate = $Matches[1] }
        elseif ($certificate -ne $Matches[1]) { throw 'Edition signatures do not match' }
        $signature | Set-Content -LiteralPath (Join-Path $auditDirectory "signature-$abi.txt") -Encoding utf8
        $manifest = (& (Join-Path $buildTools 'aapt2.exe') dump xmltree $source --file AndroidManifest.xml) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'Manifest inspection failed' }
        $manifest | Set-Content -LiteralPath (Join-Path $auditDirectory "manifest-$abi.txt") -Encoding utf8
        foreach ($component in @('LensKeyboardService', 'InputAssistService', 'ProcessTextActivity', 'InputShareActivity')) {
            if ($manifest.Contains($component) -ne ($edition -eq 'full')) { throw "Incorrect $component in $edition manifest" }
        }
        $zip = [IO.Compression.ZipFile]::OpenRead($source)
        $dex = [Text.StringBuilder]::new()
        try {
            $native = @($zip.Entries | Where-Object { $_.FullName.StartsWith('lib/') })
            $actualAbis = @($native | ForEach-Object { $_.FullName.Split('/')[1] } | Sort-Object -Unique)
            if ($actualAbis.Count -ne 1 -or $actualAbis[0] -ne $abi) { throw 'APK includes an unexpected architecture' }
            foreach ($library in @('libtranslate_jni.so', 'libmlkit_google_ocr_pipeline.so', 'liblanguage_id_l2c_jni.so')) {
                $entry = $zip.GetEntry("lib/$abi/$library")
                if ($null -eq $entry -or $entry.CompressedLength -ge $entry.Length) { throw "Missing or uncompressed native engine: $library" }
            }
            foreach ($asset in @('assets/pinyin/pinyin_simp.dict.yaml', 'assets/stroke/stroke.dict.yaml', 'res/xml/lens_keyboard.xml', 'res/xml/input_assist_service.xml')) {
                if (($null -ne $zip.GetEntry($asset)) -ne ($edition -eq 'full')) { throw "Incorrect input resource in $edition" }
            }
            foreach ($entry in $zip.Entries) {
                $stream = $entry.Open(); $memory = [IO.MemoryStream]::new()
                try { $stream.CopyTo($memory); $text = [Text.Encoding]::UTF8.GetString($memory.ToArray()) }
                finally { $stream.Dispose(); $memory.Dispose() }
                if ($secret -and $text.Contains($secret)) { throw 'Host secret detected in APK; no package exported' }
                foreach ($marker in @('local-screen-smoke-key', 'Lcom/rongji/translationqa/', 'Lcom/rongji/screentranslate/ScreenFlowFixture;')) {
                    if ($text.Contains($marker)) { throw 'Test material detected in APK; no package exported' }
                }
                if ($entry.FullName -match '^classes\d*\.dex$') { [void]$dex.Append($text) }
            }
            foreach ($type in $inputTypes) {
                if ($dex.ToString().Contains("Lcom/rongji/screentranslate/$type;") -ne ($edition -eq 'full')) { throw "Incorrect input implementation in $edition" }
            }
            $report = [ordered]@{ edition=$edition; abi=$abi; version=$version; versionCode=$output[0].versionCode; packageName=$metadata.applicationId;
                bytes=(Get-Item -LiteralPath $source).Length; sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash;
                certificateSha256=$certificate; entries=$zip.Entries.Count; inputFeatureIncluded=($edition -eq 'full');
                nativeCompressedBytes=($native | Measure-Object CompressedLength -Sum).Sum; nativeUncompressedBytes=($native | Measure-Object Length -Sum).Sum;
                secretMatches=0; testMarkerMatches=0; filename=(Split-Path -Leaf $destination) }
        } finally { $zip.Dispose() }
        Copy-Item -LiteralPath $source -Destination $destination -Force
        $reports += [pscustomobject]$report
    }
}
$auditRoot = Join-Path $artifacts ('android-' + ($version -replace '\.0$', ''))
$reports | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $auditRoot 'package-audit.json') -Encoding utf8
$hashFile = Join-Path $artifacts 'SHA256SUMS.txt'
$previous = if (Test-Path -LiteralPath $hashFile) { @(Get-Content -LiteralPath $hashFile | Where-Object { (($_ -split '  ', 2)[-1]) -notin $reports.filename }) } else { @() }
@($previous) + @($reports | ForEach-Object { $_.sha256 + '  ' + $_.filename }) | Set-Content -LiteralPath $hashFile -Encoding utf8
$reports | Select-Object edition,abi,@{Name='MiB';Expression={[math]::Round($_.bytes / 1MB, 2)}},filename | Format-Table -AutoSize
