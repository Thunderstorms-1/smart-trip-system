$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $projectRoot 'smart-trip-frontend')
try{& npm.cmd run dev}finally{Pop-Location}
