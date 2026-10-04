#requires -Version 7.0
param([ValidateRange(1024,65535)][int]$Port=5173)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$frontendRoot=Join-Path $projectRoot 'smart-trip-frontend'
$identityPath=[IO.Path]::GetFullPath($frontendRoot).Replace('\','/').TrimEnd('/').ToLowerInvariant()
$projectId=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($identityPath))).ToLowerInvariant()
$url="http://127.0.0.1:$Port"
try {
  $health=Invoke-RestMethod "$url/__smarttrip_health" -TimeoutSec 2
  if($health.app -eq 'smarttrip' -and $health.projectId -eq $projectId){Write-Host "This project's frontend is already running: $url";return}
}catch{}
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Parse('127.0.0.1'),$Port)
try{$listener.Start()}catch{throw "Port $Port is occupied by another service. Confirm that service before stopping it, or choose ./scripts/Start-Frontend.ps1 -Port 5174."}finally{$listener.Stop()}
Push-Location -LiteralPath $frontendRoot
try{& npm.cmd run dev -- --port $Port;if($LASTEXITCODE -ne 0){throw 'Frontend startup failed'}}finally{Pop-Location}
