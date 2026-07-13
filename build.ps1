# Builds VPDMirror.jar with the local JDK against the server jar + Vivecraft extension.
$ErrorActionPreference = "Stop"

$jdk    = "C:\Program Files\Java\jdk-25.0.2\bin"
$server = "C:\단타\1.21.8바이브"
$root   = Split-Path -Parent $MyInvocation.MyCommand.Path

# purpur-api + adventure + joml all live in the server's libraries folder
$libs = (Get-ChildItem "$server\libraries" -Recurse -Filter *.jar).FullName
$cp = ($libs + "$server\plugins\Vivecraft-Spigot-Extension-1.3.7-1.jar") -join ";"

$out = "$root\build\classes"
if (Test-Path "$root\build") { Remove-Item -Recurse -Force "$root\build" }
New-Item -ItemType Directory -Force $out | Out-Null

$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\javac.exe" --release 21 -encoding UTF-8 -classpath $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Copy-Item "$root\resources\*" $out -Recurse -Force

& "$jdk\jar.exe" --create --file "$root\build\VPDMirror-1.0.0.jar" -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "Built: $root\build\VPDMirror-1.0.0.jar"
