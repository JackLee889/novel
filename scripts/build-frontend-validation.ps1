param(
    [string]$FrontendJavaHome = (Join-Path $PSScriptRoot '..\.reader-tools\jdk\jdk-21.0.8+9'),
    [string]$MavenCommand = (Join-Path $PSScriptRoot '..\.reader-tools\maven\apache-maven-3.9.9\bin\mvn.cmd')
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $root
$baseline = (Get-Content -LiteralPath 'doc\frontend-evidence\baseline-path.txt' -Raw).Trim()
$packageBackup = Join-Path $baseline 'packages'
New-Item -ItemType Directory -Force $packageBackup | Out-Null
foreach ($artifact in @('novel-front\target\novel-front-5.3.3.jar','novel-front\target\build\novel-front.zip')) {
    $saved = Join-Path $packageBackup (Split-Path $artifact -Leaf)
    if ((Test-Path -LiteralPath $artifact) -and !(Test-Path -LiteralPath $saved)) { Copy-Item -LiteralPath $artifact -Destination $saved }
}
$env:JAVA_HOME = (Resolve-Path -LiteralPath $FrontendJavaHome).Path
& $MavenCommand -pl novel-front -am '-Dmaven.test.skip=false' package > 'doc\frontend-evidence\maven-package.log' 2>&1
if ($LASTEXITCODE -ne 0) { throw '前台构建或测试失败，请检查 frontend-evidence\maven-package.log' }
& $MavenCommand -pl novel-front '-DincludeScope=test' '-Dmdep.outputFile=target/reader-test-classpath.txt' dependency:build-classpath > 'doc\frontend-evidence\maven-classpath.log' 2>&1
if ($LASTEXITCODE -ne 0) { throw '验证 classpath 生成失败' }
Write-Output '前台测试与打包完成，上一版 JAR/ZIP 已备份。'
