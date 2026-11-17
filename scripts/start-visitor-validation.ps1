param([int]$Port = 18988)
$ErrorActionPreference = 'Stop'
$visitorRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $visitorRoot
$visitorJava = Join-Path $visitorRoot '.reader-tools\jdk\jdk-21.0.8+9\bin\java.exe'
$visitorClasspath = (Get-Content -LiteralPath 'novel-front\target\visitor-test-classpath.txt' -Raw).Trim()
$visitorArgFile = Join-Path $visitorRoot ".reader-tools\visitor-validation-$Port.args"
$visitorArguments = @('-Xmx768m', '-cp', ('"' + ('novel-front\target\test-classes;novel-front\target\classes;novel-common\target\classes;' + $visitorClasspath).Replace('\', '/') + '"'),
    'com.java2nb.novel.visitor.VisitorStatsFixtureApplication', "--server.port=$Port",
    '--spring.thymeleaf.prefix=file:novel-front/src/main/resources/templates/',
    '--spring.web.resources.static-locations=file:novel-front/src/main/resources/static/')
$visitorArguments | Set-Content -LiteralPath $visitorArgFile -Encoding utf8
& $visitorJava "@$visitorArgFile"
exit $LASTEXITCODE
