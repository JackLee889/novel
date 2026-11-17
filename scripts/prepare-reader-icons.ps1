$ErrorActionPreference = 'Stop'
$dest = Join-Path $PSScriptRoot '..\novel-front\src\main\resources\static\reader\icons'
New-Item -ItemType Directory -Force $dest | Out-Null
$version = '3.35.0'
foreach ($name in @('list','bookmark','arrows-maximize','headphones','moon','player-play','player-pause','player-skip-back','player-skip-forward','search','menu-2','x','book-2','book','pin','arrow-right','user','pencil')) {
    $target = Join-Path $dest "$name.svg"
    if (!(Test-Path -LiteralPath $target)) {
        Invoke-WebRequest -Uri "https://cdn.jsdelivr.net/npm/@tabler/icons@$version/icons/outline/$name.svg" -OutFile $target
    }
    [xml](Get-Content -LiteralPath $target -Raw) | Out-Null
}
if (!(Test-Path -LiteralPath (Join-Path $dest 'LICENSE'))) {
    Invoke-WebRequest -Uri "https://cdn.jsdelivr.net/npm/@tabler/icons@$version/LICENSE" -OutFile (Join-Path $dest 'LICENSE')
}
Write-Output 'Tabler 3.35.0 原始 SVG 图标与 MIT 许可证已保存。'
