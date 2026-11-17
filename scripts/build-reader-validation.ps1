param(
    [string]$ReaderJavaHome = (Join-Path $PSScriptRoot '..\.reader-tools\jdk\jdk-21.0.8+9'),
    [string]$MavenCommand = (Join-Path $PSScriptRoot '..\.reader-tools\maven\apache-maven-3.9.9\bin\mvn.cmd')
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $root
if (!(Test-Path -LiteralPath $ReaderJavaHome)) { throw '请配置 Java 21 路径' }
if (!(Test-Path -LiteralPath $MavenCommand)) { throw '请配置 Maven 3.9.9 路径' }
# Package copies source templates/static files into templates/green. Preserve that baseline.
$backup = Join-Path $root ('backups\reader-build-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $backup | Out-Null
Copy-Item -LiteralPath 'templates\green' -Destination (Join-Path $backup 'templates-green') -Recurse
$env:JAVA_HOME = (Resolve-Path -LiteralPath $ReaderJavaHome).Path
New-Item -ItemType Directory -Force 'doc\reader-evidence' | Out-Null
& $MavenCommand -pl novel-common -am '-Dmaven.test.skip=true' install > 'doc\reader-evidence\maven-common-install.log' 2>&1
if ($LASTEXITCODE -ne 0) { throw 'common 模块构建失败，请检查日志' }
& $MavenCommand -pl novel-front -am '-Dmaven.test.skip=false' package > 'doc\reader-evidence\maven-package.log' 2>&1
if ($LASTEXITCODE -ne 0) { throw '前台测试或打包失败，请检查日志' }
& $MavenCommand -pl novel-front '-DincludeScope=test' '-Dmdep.outputFile=target/reader-test-classpath.txt' dependency:build-classpath > 'doc\reader-evidence\maven-classpath.log' 2>&1
if ($LASTEXITCODE -ne 0) { throw '测试 classpath 生成失败，请检查日志' }
Write-Output "前台 package 和测试完成；生成模板基线保存在 $backup。"
