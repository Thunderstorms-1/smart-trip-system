param([string]$RedisExecutable='redis-server.exe',[int]$Port=6379)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$runtimeRoot=Join-Path $projectRoot '.local/redis'
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
# Local development instance only. Does not load, delete or replace existing Redis data.
& $RedisExecutable --bind 127.0.0.1 --port $Port --save '' --appendonly no --protected-mode yes --dir $runtimeRoot
