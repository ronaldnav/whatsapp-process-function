<#
.SYNOPSIS
    Compila y despliega WhatsApp Process Function en su Function App.
.DESCRIPTION
    Resource group, nombre de la Function App y nombre del artefacto se leen directamente de
    pom.xml (única fuente de verdad: <properties><resourceGroup>/<functionAppName> y
    <artifactId>/<version>). No se recalculan ni duplican aquí.
.EXAMPLE
    ./deploy.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

function Log([string]$msg) {
    Write-Host "[$([datetime]::Now.ToString('HH:mm:ss'))] $msg" -ForegroundColor Cyan
}

Push-Location $PSScriptRoot
try {
    [xml]$pom = Get-Content -Path (Join-Path $PSScriptRoot "pom.xml")
    $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $ns.AddNamespace("m", "http://maven.apache.org/POM/4.0.0")

    $ArtifactId = $pom.SelectSingleNode("/m:project/m:artifactId", $ns).InnerText
    $Version = $pom.SelectSingleNode("/m:project/m:version", $ns).InnerText
    $ResourceGroup = $pom.SelectSingleNode("/m:project/m:properties/m:resourceGroup", $ns).InnerText
    $FuncApp = $pom.SelectSingleNode("/m:project/m:properties/m:functionAppName", $ns).InnerText
    $ArtifactName = "$ArtifactId-$Version"

    if ([string]::IsNullOrWhiteSpace($ResourceGroup) -or [string]::IsNullOrWhiteSpace($FuncApp)) {
        throw "No se pudo leer resourceGroup/functionAppName desde pom.xml."
    }

    Write-Host ""
    Write-Host "==== Deploy: WhatsApp Process Function -> $FuncApp ($ResourceGroup) ====" -ForegroundColor Yellow
    Log "Artefacto: $ArtifactName.jar"

    Log "Paso 1/2: Maven build"
    mvn clean package --no-transfer-progress
    if ($LASTEXITCODE -ne 0) { throw "Maven build falló (exit $LASTEXITCODE)" }

    $stagingDir = "target\azure-functions\$FuncApp"
    if (-not (Test-Path $stagingDir)) {
        throw "Staging dir no encontrado: $stagingDir"
    }

    $funcIgnore = Join-Path $PSScriptRoot ".funcignore"
    if (Test-Path $funcIgnore) {
        Copy-Item -Path $funcIgnore -Destination $stagingDir -Force
    }

    Log "Paso 2/2: Publicando en Azure -> $FuncApp"
    Push-Location $stagingDir
    try {
        func azure functionapp publish $FuncApp
        if ($LASTEXITCODE -ne 0) { throw "func publish falló (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    Write-Host ""
    Write-Host "================================================================" -ForegroundColor Green
    Write-Host " WhatsApp Process Function desplegado" -ForegroundColor Green
    Write-Host "  URL: https://$FuncApp.azurewebsites.net/api/whatsapp" -ForegroundColor Green
    Write-Host "================================================================" -ForegroundColor Green
} finally {
    Pop-Location
}
