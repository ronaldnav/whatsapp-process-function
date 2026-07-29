param(
    [Parameter()]
    [string]$ResourceGroup,

    [Parameter()]
    [string]$AppName,

    [Parameter()]
    [string]$SettingsFile = "local.settings.json"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    throw "Azure CLI (az) is required to update Function App settings."
}

$resolvedResourceGroup = $ResourceGroup
$resolvedAppName = $AppName

if (-not $resolvedResourceGroup -or -not $resolvedAppName) {
    if (Test-Path "pom.xml") {
        $pom = [xml](Get-Content pom.xml -Raw)

        if (-not $resolvedResourceGroup) {
            $resolvedResourceGroup = $pom.project.properties.'azure.functions.resourceGroup'
        }

        if (-not $resolvedAppName) {
            $resolvedAppName = $pom.project.properties.'azure.functions.appName'
        }

        if (-not $resolvedResourceGroup -or -not $resolvedAppName) {
            $pluginConfig = $pom.project.build.plugins.plugin | Where-Object { $_.artifactId -eq 'azure-functions-maven-plugin' }

            if (-not $resolvedResourceGroup) {
                $resolvedResourceGroup = $pluginConfig.configuration.resourceGroup
            }

            if (-not $resolvedAppName) {
                $resolvedAppName = $pluginConfig.configuration.appName
            }
        }
    }
}

if (-not $resolvedResourceGroup -or -not $resolvedAppName) {
    throw "Could not resolve both ResourceGroup and AppName. Provide them as parameters or ensure pom.xml contains the deployment values."
}

$settingsPath = if ([System.IO.Path]::IsPathRooted($SettingsFile)) {
    $SettingsFile
} else {
    Join-Path (Get-Location) $SettingsFile
}

if (-not (Test-Path $settingsPath)) {
    throw "Settings file '$SettingsFile' was not found."
}

$config = Get-Content $settingsPath -Raw | ConvertFrom-Json
if (-not $config.Values) {
    throw "No app settings were found in '$SettingsFile'."
}

$settingsToApply = New-Object System.Collections.Generic.List[string]
foreach ($property in $config.Values.PSObject.Properties) {
    if ($property.Name -eq 'IsEncrypted') {
        continue
    }

    $settingsToApply.Add("$($property.Name)=$([string]$property.Value)")
}

$queueName = $config.Values.QUEUE_NAME
$poisonQueueName = if ($config.Values.PROCESS_POISON_QUEUE_NAME) {
    [string]$config.Values.PROCESS_POISON_QUEUE_NAME
} elseif ($queueName) {
    "$queueName-poison"
} else {
    "demolab-poison-queue"
}

if (-not ($settingsToApply -contains "PROCESS_POISON_QUEUE_NAME=$poisonQueueName")) {
    $settingsToApply.Add("PROCESS_POISON_QUEUE_NAME=$poisonQueueName")
}

$adobeSslValidation = if ($config.Values.ADOBE_DISABLE_SSL_VALIDATION) {
    [string]$config.Values.ADOBE_DISABLE_SSL_VALIDATION
} else {
    "true"
}

if (-not ($settingsToApply -contains "ADOBE_DISABLE_SSL_VALIDATION=$adobeSslValidation")) {
    $settingsToApply.Add("ADOBE_DISABLE_SSL_VALIDATION=$adobeSslValidation")
}

$queueStorageConnection = $config.Values.QueueStorage
$queueStorageAccount = $null
$storageEndpointSuffix = "core.windows.net"

if ($queueStorageConnection -and $queueStorageConnection -match 'AccountName=([^;]+)') {
    $queueStorageAccount = $matches[1]
}

if (-not $queueStorageAccount) {
    $queueStorageAccount = "stgdemolabv2"
}

$queueStorageQueueUri = "https://$queueStorageAccount.queue.$storageEndpointSuffix"
$queueStorageBlobUri = "https://$queueStorageAccount.blob.$storageEndpointSuffix"

$managedIdentitySettings = @(
    "QueueStorage__accountName=$queueStorageAccount",
    "QueueStorage__credential=managedidentity",
    "QueueStorage__queueServiceUri=$queueStorageQueueUri",
    "QueueStorage__blobServiceUri=$queueStorageBlobUri"
)

foreach ($setting in $managedIdentitySettings) {
    if (-not ($settingsToApply -contains $setting)) {
        $settingsToApply.Add($setting)
    }
}

if ($settingsToApply.Count -eq 0) {
    Write-Host "No app settings to apply."
    return
}

Write-Host "Applying $($settingsToApply.Count) app settings to Function App '$resolvedAppName'..."
az functionapp config appsettings set --resource-group $resolvedResourceGroup --name $resolvedAppName --settings $settingsToApply | Out-Null

Write-Host "App settings applied successfully."
