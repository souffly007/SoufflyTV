# Construit les installeurs Windows de SoufflyTV (.msi et .exe), libVLC comprise.
#
#   powershell -ExecutionPolicy Bypass -File windows\build-windows.ps1
#
# Prérequis sur le PC Windows :
#   - JDK 21 (avec jpackage) :  winget install EclipseAdoptium.Temurin.21.JDK
#   - SDK Android (le projet contient aussi l'app Android) : Android Studio installé,
#     ou variable ANDROID_HOME, ou sdk.dir dans local.properties
# WiX (pour le .msi) est téléchargé automatiquement par Gradle.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# --- JDK ---
$jpackage = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\jpackage.exe"))) {
    $jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
} else {
    $cmd = Get-Command jpackage -ErrorAction SilentlyContinue
    if ($cmd) { $jpackage = $cmd.Source }
}
if (-not $jpackage) {
    Write-Host "JDK 21 introuvable. Installe-le avec :" -ForegroundColor Yellow
    Write-Host "    winget install EclipseAdoptium.Temurin.21.JDK"
    Write-Host "puis ferme et rouvre PowerShell."
    exit 1
}
Write-Host "JDK : $jpackage"

# --- SDK Android ---
$hasSdk = $env:ANDROID_HOME -or $env:ANDROID_SDK_ROOT -or `
    ((Test-Path "local.properties") -and (Select-String -Path "local.properties" -Pattern "^sdk.dir" -Quiet))
if (-not $hasSdk) {
    $default = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $default) {
        $escaped = $default.Replace("\", "\\").Replace(":", "\:")
        Add-Content -Path "local.properties" -Value "sdk.dir=$escaped"
        Write-Host "SDK Android : $default"
    } else {
        Write-Host "SDK Android introuvable : installe Android Studio, ou indique son chemin dans local.properties (sdk.dir=...)." -ForegroundColor Yellow
        exit 1
    }
}

# --- libVLC embarquée ---
& (Join-Path $PSScriptRoot "prepare-vlc.ps1")

# --- Installeurs ---
& .\gradlew.bat :composeApp:packageMsi :composeApp:packageExe
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$out = Join-Path $root "composeApp\build\compose\binaries\main"
Write-Host ""
Write-Host "Installeurs prêts :" -ForegroundColor Green
Get-ChildItem -Path $out -Recurse -Include *.msi, *.exe | ForEach-Object { Write-Host "  $($_.FullName)" }
