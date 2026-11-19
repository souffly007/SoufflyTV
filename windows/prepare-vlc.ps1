# Télécharge libVLC (VLC 3, Windows 64 bits) et la place dans composeApp/resources/windows/vlc,
# pour qu'elle soit livrée dans l'installeur : plus besoin d'installer VLC à côté.
#
#   powershell -ExecutionPolicy Bypass -File windows\prepare-vlc.ps1
param(
    [string]$Version = "3.0.21"
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # téléchargement beaucoup plus rapide sous PowerShell 5

$root = Split-Path -Parent $PSScriptRoot
$dst = Join-Path $root "composeApp\resources\windows\vlc"

if (Test-Path (Join-Path $dst "libvlc.dll")) {
    Write-Host "libVLC déjà présente dans $dst"
    exit 0
}

$url = "https://download.videolan.org/pub/videolan/vlc/$Version/win64/vlc-$Version-win64.zip"
$tmp = Join-Path $env:TEMP "soufflytv-vlc-$Version"
$zip = Join-Path $tmp "vlc.zip"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

Write-Host "Téléchargement de VLC $Version (64 bits)..."
Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip

# Vérification avec l'empreinte publiée par VideoLAN
$shaText = (Invoke-WebRequest -UseBasicParsing -Uri "$url.sha256").Content
if ($shaText -is [byte[]]) { $shaText = [System.Text.Encoding]::ASCII.GetString($shaText) }
$expected = ($shaText.Trim() -split "\s+")[0].ToLower()
$actual = (Get-FileHash -Algorithm SHA256 -Path $zip).Hash.ToLower()
if ($expected -ne $actual) {
    throw "Empreinte SHA-256 incorrecte pour $url (attendue $expected, obtenue $actual)"
}
Write-Host "Empreinte vérifiée : $actual"

Expand-Archive -Path $zip -DestinationPath $tmp -Force
$src = Join-Path $tmp "vlc-$Version"

New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item (Join-Path $src "libvlc.dll") $dst
Copy-Item (Join-Path $src "libvlccore.dll") $dst
Copy-Item (Join-Path $src "plugins") (Join-Path $dst "plugins") -Recurse

# Modules inutiles pour SoufflyTV (interfaces de VLC, scripts, visualisations) : installeur plus léger
foreach ($folder in @("gui", "lua", "visualization")) {
    $path = Join-Path $dst "plugins\$folder"
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
}
# Le cache des modules sera reconstruit par libVLC au premier lancement
$cache = Join-Path $dst "plugins\plugins.dat"
if (Test-Path $cache) { Remove-Item -Force $cache }

Remove-Item -Recurse -Force $tmp
$size = "{0:N0}" -f ((Get-ChildItem -Recurse $dst | Measure-Object -Property Length -Sum).Sum / 1MB)
Write-Host "libVLC prête dans $dst ($size Mo)"
