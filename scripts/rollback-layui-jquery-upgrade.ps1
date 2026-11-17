param([string]$Release = '')
$ErrorActionPreference = 'Stop'
$layuiJqueryWorkspace = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $layuiJqueryWorkspace
$layuiJqueryPreviousRelease = $env:NOVEL_LAYUI_JQUERY_RELEASE
try {
    if ($Release) { $env:NOVEL_LAYUI_JQUERY_RELEASE = $Release }
    python (Join-Path $PSScriptRoot 'release-layui-jquery-upgrade.py') rollback
    if ($LASTEXITCODE -ne 0) { throw 'Layui/jQuery rollback failed' }
}
finally { $env:NOVEL_LAYUI_JQUERY_RELEASE = $layuiJqueryPreviousRelease; Pop-Location }
