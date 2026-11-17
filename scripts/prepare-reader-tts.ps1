param(
    [string]$Archive = 'C:\Users\lylboy\Documents\ChatGPT\tts\vendor\downloads\sherpa-onnx-wasm-simd-1.13.8-matcha-icefall-zh-en.tar.bz2',
    [string]$Destination = (Join-Path $PSScriptRoot '..\reader-resources')
)
$ErrorActionPreference = 'Stop'
$version = '1.13.8-matcha-icefall-zh-en'
$name = "sherpa-onnx-wasm-simd-$version"
$expected = 'bf891447a28294e43b3925e35d52fec550bd7ff1dfa051fb3026da44655e22d6'
$source = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/$name.tar.bz2"
if (!(Test-Path -LiteralPath $Archive)) {
    $downloadDir = Join-Path $PSScriptRoot '..\.reader-tools\downloads'
    New-Item -ItemType Directory -Force $downloadDir | Out-Null
    $Archive = Join-Path $downloadDir "$name.tar.bz2"
    if (!(Test-Path -LiteralPath $Archive)) { Invoke-WebRequest -Uri $source -OutFile $Archive }
}
if ((Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw '资源压缩包 SHA-256 不匹配，未复制运行资源。'
}
$staging = Join-Path $PSScriptRoot "..\.reader-tools\unpacked-$version"
New-Item -ItemType Directory -Force $staging | Out-Null
& tar -xjf $Archive -C $staging
if ($LASTEXITCODE -ne 0) { throw '无法解包 Sherpa 资源' }
$dest = Join-Path $Destination $version
New-Item -ItemType Directory -Force $dest | Out-Null
$assets = @()
foreach ($file in @('sherpa-onnx-tts.js','sherpa-onnx-wasm-main-tts.js','sherpa-onnx-wasm-main-tts.wasm','sherpa-onnx-wasm-main-tts.data')) {
    $origin = Join-Path (Join-Path $staging $name) $file
    $hash = (Get-FileHash -LiteralPath $origin -Algorithm SHA256).Hash.ToLowerInvariant()
    $target = Join-Path $dest $file
    if (Test-Path -LiteralPath $target) {
        if ((Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $hash) {
            throw "版本目录已有不同文件：$target。请使用另一个资源根目录，保留旧文件。"
        }
    } else { Copy-Item -LiteralPath $origin -Destination $target }
    if ((Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $hash) { throw "复制校验失败：$file" }
    $assets += [ordered]@{name=$file;url="/reader/tts/$version/$file";bytes=(Get-Item -LiteralPath $target).Length;sha256=$hash}
}
$manifest = [ordered]@{engineVersion='1.13.8';modelId='matcha-icefall-zh-en';speakerId=0;normalizationVersion='reader-text-v1';baseUrl="/reader/tts/$version/";requiresIsolation=$false;archiveSha256=$expected;source=$source;totalBytes=($assets | ForEach-Object { $_.bytes } | Measure-Object -Sum).Sum;assets=$assets}
$manifestPath = Join-Path $PSScriptRoot '..\novel-front\src\main\resources\static\reader\tts-manifest.json'
New-Item -ItemType Directory -Force (Split-Path $manifestPath) | Out-Null
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $dest 'asset-manifest.json') -Encoding utf8
$licensePath = Join-Path $dest 'LICENSE'
if (!(Test-Path -LiteralPath $licensePath)) {
    Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/LICENSE' -OutFile $licensePath
}
Write-Output "已验证 $($assets.Count) 个同版资源，$($manifest.totalBytes) 字节。资源根目录：$Destination"
