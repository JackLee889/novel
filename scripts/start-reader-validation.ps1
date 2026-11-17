param(
    [int]$Port = 18983,
    [string]$ReaderJavaHome = (Join-Path $PSScriptRoot '..\.reader-tools\jdk\jdk-21.0.8+9')
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $root
$java = Join-Path $ReaderJavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) { throw '需要 Java 21。准备本地工具目录或使用验证文档中的命令。' }
$cpFile = Join-Path $root 'novel-front\target\reader-test-classpath.txt'
if (!(Test-Path -LiteralPath $cpFile)) { throw '请先按验证文档构建 test-compile 并生成 reader-test-classpath.txt' }
$cp = (Get-Content -LiteralPath $cpFile -Raw).Trim()
$argFile = Join-Path $root ".reader-tools\reader-validation-$Port.args"
@('-Xmx1g','-cp',('"' + ("novel-front\target\test-classes;novel-front\target\classes;novel-common\target\classes;"+$cp).Replace('\','/') + '"'),
  'com.java2nb.novel.reader.ReaderFixtureApplication',"--server.port=$Port",
  '--spring.thymeleaf.prefix=file:novel-front/src/main/resources/templates/',
  '--spring.web.resources.static-locations=file:novel-front/src/main/resources/static/',
  ('"--reader.tts.resource-directory=' + $root.Replace('\','/') + '/reader-resources"')) | Set-Content -LiteralPath $argFile -Encoding utf8
& $java "@$argFile"
exit $LASTEXITCODE
