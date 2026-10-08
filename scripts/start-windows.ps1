# Launch without changing Windows network settings or writing the API key to disk.
[CmdletBinding()]
param(
  [switch]$UseWindowsProxy,
  [string]$ProxyUrl,
  [switch]$FromSource
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$desktopRoot = Join-Path $projectRoot 'desktop'
$previousProxy = $env:LENS_PROXY_URL
$previousKey = $env:DEEPSEEK_API_KEY
$previousElectronMode = $env:ELECTRON_RUN_AS_NODE
try {
  if ($ProxyUrl) {
    $env:LENS_PROXY_URL = $ProxyUrl
  } elseif ($UseWindowsProxy) {
    $settings = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $loopback = [regex]::Match([string]$settings.ProxyServer, '(?:127\.0\.0\.1|localhost):[0-9]+').Value
    if ($settings.ProxyEnable -ne 1 -or -not $loopback) {
      throw 'No enabled Windows loopback proxy was found. Supply -ProxyUrl explicitly, or launch without -UseWindowsProxy.'
    }
    $env:LENS_PROXY_URL = 'http://' + $loopback
  }
  if (-not $env:DEEPSEEK_API_KEY) {
    $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
    if (-not $env:DEEPSEEK_API_KEY) { $env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Machine') }
  }
  $env:ELECTRON_RUN_AS_NODE = $null
  if ($FromSource) {
    $runtime = Join-Path $desktopRoot 'node_modules\electron\dist\electron.exe'
    if (-not (Test-Path -LiteralPath $runtime)) { throw 'Electron runtime missing. Run npm ci and npm run build in desktop first.' }
    & $runtime $desktopRoot
  } else {
    $executable = Join-Path $desktopRoot 'release\Lens-Translate-0.1.0-x64.exe'
    if (-not (Test-Path -LiteralPath $executable)) { throw 'Portable executable missing. Run npm run dist in desktop first, or supply -FromSource.' }
    & $executable
  }
} finally {
  $env:LENS_PROXY_URL = $previousProxy
  $env:DEEPSEEK_API_KEY = $previousKey
  $env:ELECTRON_RUN_AS_NODE = $previousElectronMode
}
