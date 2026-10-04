param([int]$Port=8080)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$jar=Join-Path $projectRoot 'smart-trip-backend/target/smart-trip-backend-0.0.1-SNAPSHOT.jar'
if(-not(Test-Path -LiteralPath $jar)){throw 'Run scripts/Build-Backend.ps1 first'}
Push-Location (Join-Path $projectRoot 'smart-trip-backend')
try{& java -jar $jar ('--server.port='+$Port)}finally{Pop-Location}
