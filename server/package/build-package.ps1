#Requires -Version 5.1
# Builds the installation package for another shop:
#   dist\BahaBarla-Setup\       the folder
#   dist\BahaBarla-Setup.zip    the same, zipped, ready to send
#
# The service is published self-contained, so the shop's PC needs no .NET.
# Nothing from this office goes in: no appsettings with its database list,
# no appsettings.Local.json with its sa password. install.ps1 writes fresh
# settings on the target machine.
#
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File server\package\build-package.ps1
# Build the APK first:  gradlew assembleRelease

$ErrorActionPreference = 'Stop'
$PackageSrc = $PSScriptRoot
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Dist = Join-Path $Repo 'dist'
$Out = Join-Path $Dist 'BahaBarla-Setup'
$Zip = Join-Path $Dist 'BahaBarla-Setup.zip'
$Apk = Join-Path $Repo 'app\build\outputs\apk\release\app-release.apk'

if (-not (Test-Path $Apk)) { throw "No APK at $Apk -- run gradlew assembleRelease first." }

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
if (Test-Path $Zip) { Remove-Item $Zip -Force }
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$ServiceOut = Join-Path $Out 'BahaBarlaApi'
& dotnet publish (Join-Path $Repo 'server\BahaBarlaApi\BahaBarlaApi.csproj') `
    -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true -p:DebugType=none -o $ServiceOut
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed ($LASTEXITCODE)" }

# This office's settings stay here; the installer writes the shop's own.
Get-ChildItem $ServiceOut -Filter 'appsettings*.json' | Remove-Item -Force
$leak = Get-ChildItem $Out -Recurse -File |
    Where-Object { $_.Name -like 'appsettings*' -or $_.Name -like '*.pdb' }
if ($leak) { throw "Refusing to package machine-local files: $($leak.FullName -join ', ')" }

# Windows PowerShell 5.1 reads a .ps1 without a BOM as the ANSI code page and
# would mangle the Russian messages, so the scripts are written back with one.
$bom = New-Object Text.UTF8Encoding $true
foreach ($name in 'install.ps1', 'uninstall.ps1', 'INSTRUKCIYA.txt') {
    $text = [IO.File]::ReadAllText((Join-Path $PackageSrc $name))
    [IO.File]::WriteAllText((Join-Path $Out $name), $text.Replace("`r`n", "`n").Replace("`n", "`r`n"), $bom)
}
foreach ($name in 'install.cmd', 'uninstall.cmd') {
    $text = [IO.File]::ReadAllText((Join-Path $PackageSrc $name))
    [IO.File]::WriteAllText((Join-Path $Out $name), $text.Replace("`r`n", "`n").Replace("`n", "`r`n"), (New-Object Text.ASCIIEncoding))
}
Copy-Item $Apk (Join-Path $Out 'app-release.apk')

# Every name in the package is ASCII: Windows PowerShell's ZipFile writes names
# in the local code page, and a Cyrillic one arrives garbled on another locale.
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($Out, $Zip, [IO.Compression.CompressionLevel]::Optimal, $true)

$size = [math]::Round((Get-Item $Zip).Length / 1MB, 1)
Write-Host "Package: $Zip ($size MB)"
