param([int]$Port = 18984, [switch]$LegacyReader)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $root
$java = Join-Path $root '.reader-tools\jdk\jdk-21.0.8+9\bin\java.exe'
$cpFile = Join-Path $root 'novel-front\target\reader-test-classpath.txt'
$cp = (Get-Content -LiteralPath $cpFile -Raw).Trim()
$argFile = Join-Path $root ".reader-tools\frontend-validation-$Port.args"
$arguments = @('-Xmx1g','-cp',('"' + ("novel-front\target\test-classes;novel-front\target\classes;novel-common\target\classes;"+$cp).Replace('\','/') + '"'),
  'com.java2nb.novel.reader.FrontendFixtureApplication',"--server.port=$Port",
  '--spring.thymeleaf.prefix=file:novel-front/src/main/resources/templates/',
  '--spring.web.resources.static-locations=file:novel-front/src/main/resources/static/',
  ('"--reader.tts.resource-directory=' + $root.Replace('\','/') + '/reader-resources"'))
if ($LegacyReader) { $arguments += '--reader.enabled=false' }
$arguments | Set-Content -LiteralPath $argFile -Encoding utf8
& $java "@$argFile"
exit $LASTEXITCODE
