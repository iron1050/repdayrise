<#
.SYNOPSIS
  Builds a signed RepDayrise MSIX and installs (or updates) it for the current user.

.DESCRIPTION
  Each run stamps a newer package version (Major.Minor from the manifest, then the day and minute), so it
  installs over the previous build and keeps your data. Package.appxmanifest itself is left unchanged.
  Afterwards RepDayrise is in Start. Right-click it there and choose Uninstall to remove it along with its data.

.PARAMETER Platform
  x64 or ARM64. Defaults to this PC's architecture.

.PARAMETER SharingUrl
  Optional default sharing server baked into the build (users can still change it under Partners).

.PARAMETER NoInstall
  Build the package only.
#>
[CmdletBinding()]
param(
    [ValidateSet('x64', 'ARM64')] [string] $Platform = $(if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'ARM64' } else { 'x64' }),
    [string] $SharingUrl = '',
    [switch] $NoInstall
)

$ErrorActionPreference = 'Stop'
$windowsDir = Split-Path $PSScriptRoot -Parent
$project = Join-Path $windowsDir 'src\RepDayrise.App\RepDayrise.App.csproj'
$manifest = Join-Path $windowsDir 'src\RepDayrise.App\Package.appxmanifest'
$packagesDir = Join-Path $windowsDir 'AppPackages'

if (-not (Test-Path (Join-Path $windowsDir 'signing.props'))) {
    throw 'No signing certificate yet. Run scripts\New-DevCert.ps1 once first.'
}
$dotnet = (Get-Command dotnet -ErrorAction SilentlyContinue).Source
if (-not $dotnet) { $dotnet = 'C:\Program Files\dotnet\dotnet.exe' }

# Stamp a version that always increases, then put the manifest back exactly as it was.
$original = [IO.File]::ReadAllText($manifest)
$xml = [xml]$original
$base = [version]$xml.Package.Identity.Version
$now = Get-Date
$version = '{0}.{1}.{2}.{3}' -f $base.Major, $base.Minor, ($now.Date - [datetime]'2026-01-01').Days, [int]$now.TimeOfDay.TotalMinutes
Write-Host "Building RepDayrise $version ($Platform)..."

try {
    [IO.File]::WriteAllText($manifest, ($original -replace '(<Identity[^>]*\sVersion=")[^"]*(")', "`${1}$version`${2}"))
    if (Test-Path $packagesDir) { Remove-Item $packagesDir -Recurse -Force }
    & $dotnet publish $project -c Release -p:Platform=$Platform -p:GenerateAppxPackageOnBuild=true "-p:DayriseSharingUrl=$SharingUrl" -nologo -v:minimal
    if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE)." }
} finally {
    [IO.File]::WriteAllText($manifest, $original)
}

$package = Get-ChildItem $packagesDir -Recurse -Filter '*.msix' | Where-Object { $_.Name -like "*$version*" } | Select-Object -First 1
if (-not $package) { throw "No .msix found under $packagesDir." }
Write-Host "Package: $($package.FullName)"
if ($NoInstall) { return }

Add-AppxPackage -Path $package.FullName -ForceApplicationShutdown
Write-Host "Installed RepDayrise $version. Find it in Start; right-click it there to uninstall."
