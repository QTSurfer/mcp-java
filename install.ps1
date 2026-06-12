# install.ps1 — qtsurfer-mcp installer for Windows
#
# Usage:
#   irm https://raw.githubusercontent.com/QTSurfer/mcp-java/main/install.ps1 | iex
#
# Environment variables (set before running):
#   $env:VERSION     — pin a release (default: latest)
#   $env:INSTALL_DIR — binary destination (default: %LOCALAPPDATA%\qtsurfer-mcp)

$ErrorActionPreference = 'Stop'

$Repo       = 'QTSurfer/mcp-java'
$BinaryName = 'qtsurfer-mcp'

# ── helpers ───────────────────────────────────────────────────────────────────
function Write-Info    { param($Msg) Write-Host $Msg -ForegroundColor White }
function Write-Success { param($Msg) Write-Host "OK  $Msg" -ForegroundColor Green }
function Write-Warn    { param($Msg) Write-Host "!   $Msg" -ForegroundColor Yellow }
function Write-Fail    { param($Msg) Write-Host "ERR $Msg" -ForegroundColor Red; exit 1 }

# ── platform ──────────────────────────────────────────────────────────────────
function Get-Arch {
  switch ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { return 'amd64' }
    'ARM64' { return 'arm64' }
    default { Write-Fail "Unsupported architecture: $env:PROCESSOR_ARCHITECTURE" }
  }
}

function Get-NativeAsset([string]$Arch) {
  if ($Arch -eq 'amd64') { return "${BinaryName}-windows-amd64.exe" }
  return $null  # no arm64 Windows binary yet
}

# ── version ───────────────────────────────────────────────────────────────────
function Get-LatestVersion {
  $resp = Invoke-WebRequest -Uri "https://github.com/$Repo/releases/latest" `
    -MaximumRedirection 0 -ErrorAction SilentlyContinue
  $resp.Headers['Location'] -replace '.*/releases/tag/', ''
}

# ── java detection ────────────────────────────────────────────────────────────
function Find-Java {
  $candidates = @()
  if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
  $pathJava = Get-Command java -ErrorAction SilentlyContinue
  if ($pathJava) { $candidates += $pathJava.Source }

  foreach ($cmd in $candidates) {
    if (-not (Test-Path $cmd)) { continue }
    $ver = & $cmd -version 2>&1 | Select-String '"(\d+)' |
      ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -First 1
    if ($ver -and [int]$ver -ge 21) { return $cmd }
  }
  return $null
}

# ── install dir ───────────────────────────────────────────────────────────────
function Get-InstallDir {
  if ($env:INSTALL_DIR) { return $env:INSTALL_DIR }
  return Join-Path $env:LOCALAPPDATA 'qtsurfer-mcp'
}

# ── installers ────────────────────────────────────────────────────────────────
function Install-Native([string]$Version, [string]$Asset, [string]$Dest) {
  $Url = "https://github.com/$Repo/releases/download/$Version/$Asset"
  Write-Info "Downloading $Asset $Version..."
  Invoke-WebRequest -Uri $Url -OutFile $Dest
  Write-Success "Installed native binary → $Dest"
}

function Install-Jar([string]$Version, [string]$JavaCmd, [string]$InstallDir) {
  $JarName = "${BinaryName}-java-${Version}.jar"
  $Url     = "https://github.com/$Repo/releases/download/$Version/$JarName"
  $LibDir  = Join-Path $InstallDir 'lib'
  $JarDest = Join-Path $LibDir $JarName
  $Wrapper = Join-Path $InstallDir "${BinaryName}.cmd"

  New-Item -ItemType Directory -Force -Path $LibDir | Out-Null
  Write-Info "Downloading fat JAR $Version..."
  Invoke-WebRequest -Uri $Url -OutFile $JarDest
  Write-Success "JAR saved → $JarDest"

  $WrapperContent = "@echo off`r`n`"$JavaCmd`" -jar `"$JarDest`" %*`r`n"
  Set-Content -Path $Wrapper -Value $WrapperContent -Encoding ASCII
  Write-Success "Installed wrapper → $Wrapper"
}

function Install-JavaWinget {
  Write-Info "Installing Java 21 (Temurin) via winget..."
  winget install --id EclipseAdoptium.Temurin.21.JDK --silent --accept-package-agreements
  # Refresh PATH so java is found in this session
  $env:PATH = [System.Environment]::GetEnvironmentVariable('PATH', 'Machine') + ';' +
              [System.Environment]::GetEnvironmentVariable('PATH', 'User')
  Write-Success "Java 21 installed."
}

# ── PATH helper ───────────────────────────────────────────────────────────────
function Add-ToUserPath([string]$Dir) {
  $current = [System.Environment]::GetEnvironmentVariable('PATH', 'User')
  if ($current -split ';' -contains $Dir) { return }
  [System.Environment]::SetEnvironmentVariable('PATH', "$current;$Dir", 'User')
  $env:PATH = "$env:PATH;$Dir"
  Write-Warn "Added $Dir to your user PATH (restart your terminal to apply)."
}

# ── main ──────────────────────────────────────────────────────────────────────
$Arch       = Get-Arch
$Version    = if ($env:VERSION) { $env:VERSION } else { Get-LatestVersion }
$InstallDir = Get-InstallDir

Write-Host ""
Write-Info "qtsurfer-mcp installer"
Write-Host "  Platform : windows/$Arch"
Write-Host "  Version  : $Version"
Write-Host "  Install  : $InstallDir"
Write-Host ""

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

$Asset = Get-NativeAsset $Arch

if ($Asset) {
  $Dest = Join-Path $InstallDir "${BinaryName}.exe"
  Install-Native $Version $Asset $Dest
} else {
  Write-Warn "No native binary for windows/$Arch — falling back to fat JAR (requires Java 21+)."
  $JavaCmd = Find-Java
  if (-not $JavaCmd) {
    Write-Warn "Java 21+ not found."
    $answer = Read-Host "  Install Java 21 via winget? [y/N]"
    if ($answer -match '^[Yy]') {
      Install-JavaWinget
      $JavaCmd = Find-Java
      if (-not $JavaCmd) { Write-Fail "Java 21+ still not found. Open a new terminal and re-run." }
    } else {
      Write-Fail "Java 21+ required. Install it from https://adoptium.net then re-run."
    }
  } else {
    $JavaVer = & $JavaCmd -version 2>&1 | Select-Object -First 1
    Write-Success "Found Java: $JavaCmd ($JavaVer)"
  }
  Install-Jar $Version $JavaCmd $InstallDir
}

Add-ToUserPath $InstallDir

Write-Host ""
Write-Success "Done. Test with:  $BinaryName --help"
