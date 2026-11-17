param([string]$Release = '')
$ErrorActionPreference = 'Stop'
$bootstrapWorkspace = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $bootstrapWorkspace
$bootstrapPreviousRelease = $env:NOVEL_BOOTSTRAP_RELEASE
try {
    if ($Release) { $env:NOVEL_BOOTSTRAP_RELEASE = $Release }
    python (Join-Path $PSScriptRoot 'release-bootstrap-upgrade.py') rollback
    if ($LASTEXITCODE -ne 0) { throw 'Bootstrap rollback failed' }
}
finally { $env:NOVEL_BOOTSTRAP_RELEASE = $bootstrapPreviousRelease; Pop-Location }
