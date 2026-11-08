<#
.SYNOPSIS
  One-time setup: creates a self-signed code-signing certificate for sideloading RepDayrise and trusts it.

.DESCRIPTION
  Creates (or reuses) "CN=RepDayrise Dev" in your personal certificate store and adds its public half to
  Local Machine > Trusted People so Windows will install packages signed with it. That last step asks
  for administrator approval once. The thumbprint is written to windows/signing.props (git-ignored),
  which the build picks up automatically.

  The subject must match Publisher in src/RepDayrise.App/Package.appxmanifest.
#>
[CmdletBinding()]
param([string] $Subject = 'CN=RepDayrise Dev')

$ErrorActionPreference = 'Stop'
$windowsDir = Split-Path $PSScriptRoot -Parent

$cert = Get-ChildItem Cert:\CurrentUser\My |
    Where-Object { $_.Subject -eq $Subject -and $_.NotAfter -gt (Get-Date).AddDays(30) -and $_.HasPrivateKey } |
    Sort-Object NotAfter -Descending | Select-Object -First 1

if ($cert) {
    Write-Host "Reusing certificate $($cert.Thumbprint) (expires $($cert.NotAfter.ToShortDateString()))"
} else {
    $cert = New-SelfSignedCertificate -Type Custom -Subject $Subject -FriendlyName 'RepDayrise sideload signing' `
        -KeyUsage DigitalSignature -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 `
        -CertStoreLocation Cert:\CurrentUser\My -NotAfter (Get-Date).AddYears(3) `
        -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')
    Write-Host "Created certificate $($cert.Thumbprint)"
}

$trusted = Get-ChildItem Cert:\LocalMachine\TrustedPeople | Where-Object Thumbprint -eq $cert.Thumbprint
if ($trusted) {
    Write-Host 'Already trusted for package installs.'
} else {
    $cer = Join-Path ([IO.Path]::GetTempPath()) "RepDayriseDev-$($cert.Thumbprint).cer"
    Export-Certificate -Cert $cert -FilePath $cer | Out-Null
    Write-Host 'Trusting the certificate for package installs (Windows will ask for administrator approval)...'
    $shell = (Get-Process -Id $PID).Path
    $proc = Start-Process $shell -Verb RunAs -Wait -PassThru -ArgumentList @(
        '-NoProfile', '-Command', "Import-Certificate -FilePath '$cer' -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null")
    Remove-Item $cer -ErrorAction SilentlyContinue
    if ($proc.ExitCode -ne 0 -or -not (Get-ChildItem Cert:\LocalMachine\TrustedPeople | Where-Object Thumbprint -eq $cert.Thumbprint)) {
        throw 'The certificate was not added to Trusted People. Run this script again and approve the prompt.'
    }
    Write-Host 'Trusted.'
}

@"
<Project>
  <!-- Written by scripts/New-DevCert.ps1. Local to this machine; not committed. -->
  <PropertyGroup>
    <AppxPackageSigningEnabled>true</AppxPackageSigningEnabled>
    <PackageCertificateThumbprint>$($cert.Thumbprint)</PackageCertificateThumbprint>
  </PropertyGroup>
</Project>
"@ | Set-Content (Join-Path $windowsDir 'signing.props') -Encoding utf8

Write-Host "Wrote signing.props. Next: scripts\Install.ps1"
