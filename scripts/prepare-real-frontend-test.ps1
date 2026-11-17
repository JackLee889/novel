param(
    [int]$Port = 18987,
    [string]$Revision = '',
    [string]$DeploymentRoot = 'C:\Users\lylboy\Documents\Codex\2026-10-03\task\novel-plus-local'
)
$ErrorActionPreference = 'Stop'
$realRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$realRun = (Get-Content -LiteralPath (Join-Path $realRoot 'doc/frontend-realtest-current.txt') -Raw).Trim()
if ($realRun -notmatch '^frontend-realtest-\d{8}-\d{6}$') { throw 'Invalid evidence run identity' }
$realEvidence = Join-Path $realRoot "doc/$realRun"
if ($Revision -and $Revision -notmatch '^[a-z][a-z0-9-]*$') { throw 'Invalid candidate revision' }
$realSuffix = if ($Revision) { "-$Revision" } else { '' }
$realImage = "novel-plus-front:${realRun}${realSuffix}"
$realDeployment = (Resolve-Path -LiteralPath $DeploymentRoot).Path
$realComposeFile = Join-Path $realDeployment 'compose.yaml'
$realComposeForYaml = $realComposeFile.Replace('\','/')
$realGatewayConfigPath = Join-Path $realEvidence 'candidate-Caddyfile'
$realGatewayConfigForYaml = $realGatewayConfigPath.Replace('\','/')
$realOriginalConfigText = & docker compose --project-directory $realDeployment -f $realComposeFile config --format json
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve original deployment configuration' }
$realOriginalConfig = $realOriginalConfigText | ConvertFrom-Json
$realGatewayImage = $realOriginalConfig.services.gateway.image
$realGatewayConfig = Get-Content -LiteralPath (Join-Path $realDeployment 'Caddyfile') -Raw
if ($realGatewayConfig -notmatch 'reverse_proxy\s+front:8080') { throw 'Original frontend gateway route not found' }
$realGatewayConfig = $realGatewayConfig -replace 'reverse_proxy\s+front:8080', 'reverse_proxy front-realtest:8080'
[IO.File]::WriteAllText($realGatewayConfigPath, $realGatewayConfig, [Text.UTF8Encoding]::new($false))
$realReaderResources = (Resolve-Path -LiteralPath (Join-Path $realRoot 'reader-resources')).Path.Replace('\','/')
$realOverride = @"
services:
  front-realtest:
    extends:
      file: "$realComposeForYaml"
      service: front
    image: $realImage
    environment:
      PIC_SAVE_TYPE: "1"
      WEBSITE_DOMAIN: "127.0.0.1:${Port}"
      READER_ENABLED: "true"
      READER_TTS_ENABLED: "true"
      READER_TTS_RESOURCE_DIRECTORY: /opt/reader-resources
    volumes:
      - "shared-files:/data:ro"
      - "${realReaderResources}:/opt/reader-resources:ro"
  gateway-realtest:
    image: $realGatewayImage
    restart: "no"
    ports:
      - "127.0.0.1:${Port}:8080"
    volumes:
      - "${realGatewayConfigForYaml}:/etc/caddy/Caddyfile:ro"
    networks: [local, access]
    depends_on:
      front-realtest:
        condition: service_healthy
"@
$realOverridePath = Join-Path $realEvidence 'front-realtest.override.yaml'
[IO.File]::WriteAllText($realOverridePath, $realOverride, [Text.UTF8Encoding]::new($false))
[ordered]@{
    deploymentRoot = $realDeployment
    evidenceRoot = $realEvidence
    composeFile = $realComposeFile
    overrideFile = $realOverridePath
    candidateContext = (Join-Path $realEvidence "candidate-context$realSuffix")
    image = $realImage
    port = $Port
    service = 'front-realtest'
    gatewayService = 'gateway-realtest'
    gatewayConfig = $realGatewayConfigPath
    readerResources = $realReaderResources
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $realEvidence 'test-runtime.json') -Encoding utf8
Write-Output "Prepared candidate $realImage at http://127.0.0.1:$Port/. No service started."
