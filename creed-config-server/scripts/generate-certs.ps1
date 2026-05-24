<#
.SYNOPSIS
    Generates a dev-only PKI for the creed-config-server SSL Bundles.

.DESCRIPTION
    Produces:
        ca.key, ca.crt                  self-signed root CA
        server.key, server.crt          server cert signed by the CA (matches creed-pem-server)
        client.key, client.crt          client cert signed by the CA (matches creed-pem-client)
        server-keystore.p12             PKCS12 with alias 'server'  (matches creed-jks-server)
        client-keystore.p12             PKCS12 with alias 'client'  (matches creed-jks-client)
        truststore.p12                  PKCS12 holding the CA       (shared truststore)

    Requires OpenSSL 3.x and the JDK's keytool on PATH.

.EXAMPLE
    .\generate-certs.ps1
    .\generate-certs.ps1 -Force -Password 'changeit' -ValidityDays 730
    .\generate-certs.ps1 -ServerCn 'config.creed.local' -ServerSan @('DNS:config.creed.local','DNS:localhost','IP:127.0.0.1')
#>
[CmdletBinding()]
param(
    [string]   $OutputDir,
    [int]      $ValidityDays = 365,
    [string]   $Password     = 'changeit',
    [string]   $ServerCn     = 'localhost',
    [string[]] $ServerSan    = @('DNS:localhost', 'IP:127.0.0.1'),
    [string]   $ClientCn     = 'creed-config-client',
    [switch]   $Force
)

$ErrorActionPreference = 'Stop'

if (-not $OutputDir) {
    $OutputDir = Join-Path $PSScriptRoot '..\src\main\resources\certs'
}

function Assert-Tool([string]$Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $cmd) {
        throw "$Name not found on PATH. Install OpenSSL 3.x + JDK and retry. (keytool ships with the JDK; openssl can be installed via 'winget install ShiningLight.OpenSSL' or Git for Windows.)"
    }
    Write-Host ("  using {0,-8} -> {1}" -f $Name, $cmd.Source)
}

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Exe exited with code $LASTEXITCODE (args: $($Arguments -join ' '))"
    }
}

function PathOf([string]$Name) { Join-Path $OutputDir $Name }

Write-Host "Checking tooling..."
Assert-Tool 'openssl'
Assert-Tool 'keytool'

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}
$OutputDir = (Resolve-Path -LiteralPath $OutputDir).Path
Write-Host "Output directory: $OutputDir"

# Files we'll produce (and the only files we ever clean up — keeps any README/.gitkeep safe).
$caKey    = PathOf 'ca.key'
$caCrt    = PathOf 'ca.crt'
$srvKey   = PathOf 'server.key'
$srvCsr   = PathOf 'server.csr'
$srvCrt   = PathOf 'server.crt'
$cliKey   = PathOf 'client.key'
$cliCsr   = PathOf 'client.csr'
$cliCrt   = PathOf 'client.crt'
$srvP12   = PathOf 'server-keystore.p12'
$cliP12   = PathOf 'client-keystore.p12'
$trustP12 = PathOf 'truststore.p12'
$caSerial = PathOf 'ca.srl'

$generated = @($caKey, $caCrt, $srvKey, $srvCsr, $srvCrt, $cliKey, $cliCsr, $cliCrt, $srvP12, $cliP12, $trustP12, $caSerial)

if (-not $Force) {
    $existing = $generated | Where-Object { Test-Path $_ }
    if ($existing) {
        Write-Warning "These files already exist:"
        $existing | ForEach-Object { Write-Host "  $_" }
        Write-Warning "Pass -Force to overwrite. Aborting."
        return
    }
}

# Remove only known artifacts so README / .gitkeep in certs/ survive.
$generated | Where-Object { Test-Path $_ } | Remove-Item -Force

$sanList = $ServerSan -join ','
Write-Host ""

Write-Host "1/5  Generating self-signed CA"
Invoke-Native 'openssl' @(
    'req', '-x509', '-nodes', '-newkey', 'rsa:2048',
    '-days', $ValidityDays,
    '-subj', '/CN=Creed Dev Root CA/O=creed/OU=dev',
    '-keyout', $caKey,
    '-out',    $caCrt
)

function New-SignedCert {
    param(
        [string]   $Cn,
        [string]   $KeyOut,
        [string]   $CsrOut,
        [string]   $CrtOut,
        [string[]] $San,
        [string]   $ExtendedKeyUsage   # 'serverAuth' or 'clientAuth'
    )

    Invoke-Native 'openssl' @(
        'req', '-nodes', '-newkey', 'rsa:2048',
        '-subj', "/CN=$Cn/O=creed/OU=dev",
        '-keyout', $KeyOut,
        '-out',    $CsrOut
    )

    $extFile = [System.IO.Path]::GetTempFileName()
    try {
        $extLines = @("extendedKeyUsage = $ExtendedKeyUsage")
        if ($San -and $San.Count -gt 0) {
            $extLines += "subjectAltName = $($San -join ',')"
        }
        Set-Content -LiteralPath $extFile -Encoding ASCII -Value $extLines

        Invoke-Native 'openssl' @(
            'x509', '-req',
            '-in',       $CsrOut,
            '-CA',       $caCrt,
            '-CAkey',    $caKey,
            '-CAcreateserial',
            '-days',     $ValidityDays,
            '-extfile',  $extFile,
            '-out',      $CrtOut
        )
    } finally {
        Remove-Item -LiteralPath $extFile -ErrorAction SilentlyContinue
    }
}

Write-Host "2/5  Issuing server cert (CN=$ServerCn, SAN=$sanList)"
New-SignedCert -Cn $ServerCn -KeyOut $srvKey -CsrOut $srvCsr -CrtOut $srvCrt -San $ServerSan -ExtendedKeyUsage 'serverAuth'

Write-Host "3/5  Issuing client cert (CN=$ClientCn)"
New-SignedCert -Cn $ClientCn -KeyOut $cliKey -CsrOut $cliCsr -CrtOut $cliCrt -San @() -ExtendedKeyUsage 'clientAuth'

Write-Host "4/5  Bundling PKCS12 keystores"
Invoke-Native 'openssl' @(
    'pkcs12', '-export',
    '-name',     'server',
    '-inkey',    $srvKey,
    '-in',       $srvCrt,
    '-certfile', $caCrt,
    '-out',      $srvP12,
    '-passout',  "pass:$Password"
)
Invoke-Native 'openssl' @(
    'pkcs12', '-export',
    '-name',     'client',
    '-inkey',    $cliKey,
    '-in',       $cliCrt,
    '-certfile', $caCrt,
    '-out',      $cliP12,
    '-passout',  "pass:$Password"
)

Write-Host "5/5  Building PKCS12 truststore (CA only, via keytool for proper trustedCertEntry)"
if (Test-Path $trustP12) { Remove-Item -LiteralPath $trustP12 -Force }
Invoke-Native 'keytool' @(
    '-importcert',
    '-keystore',  $trustP12,
    '-storetype', 'PKCS12',
    '-storepass', $Password,
    '-alias',     'creed-ca',
    '-file',      $caCrt,
    '-noprompt'
)

# Tidy intermediate scratch (CSRs + openssl serial file).
@($srvCsr, $cliCsr, $caSerial) | Where-Object { Test-Path $_ } | Remove-Item -Force

Write-Host ""
Write-Host "Done. Files in $OutputDir :"
Get-ChildItem -LiteralPath $OutputDir -File |
    Sort-Object Name |
    ForEach-Object { Write-Host ("  {0,-24} {1,8} bytes" -f $_.Name, $_.Length) }

Write-Host ""
Write-Host "SSL Bundle mapping (application.yml):"
Write-Host "  PEM  creed-pem-server  <- server.crt + server.key (truststore: ca.crt)"
Write-Host "  PEM  creed-pem-client  <- client.crt + client.key (truststore: ca.crt)"
Write-Host "  JKS  creed-jks-server  <- server-keystore.p12 (alias 'server', pwd=$Password)"
Write-Host "  JKS  creed-jks-client  <- client-keystore.p12 (alias 'client', pwd=$Password)"
Write-Host "  JKS  truststore.p12    <- shared truststore (alias 'creed-ca', pwd=$Password)"
