#requires -Version 5.1
param(
 [ValidateSet('inspect','init','import','rebuild','delete')][string]$Command='inspect',
 [string]$Manifest='',
 [string]$DocumentId='',
 [string]$Java='java',
 [string]$Jar=''
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$artifact=if($Jar){(Resolve-Path -LiteralPath $Jar).Path}else{Join-Path $projectRoot 'smart-trip-backend/target/smart-trip-backend-0.0.1-SNAPSHOT.jar'}
if(-not(Test-Path -LiteralPath $artifact)){throw 'Build the backend jar before running the knowledge command'}
if(-not $Manifest){$Manifest=Join-Path $projectRoot 'knowledge/travel-rag/manifest.json'}
if($Command -in @('inspect','import','rebuild')){$Manifest=(Resolve-Path -LiteralPath $Manifest).Path}
if($Command -eq 'delete' -and $DocumentId -notmatch '^[A-Za-z0-9_-]{1,120}$'){throw 'A valid DocumentId is required for delete'}
$previousConfig=$env:SMARTTRIP_CONFIG
try {
 if(-not $env:SMARTTRIP_CONFIG){$env:SMARTTRIP_CONFIG=(Join-Path $projectRoot '.local/application-local.yml').Replace('\','/')}
 $arguments=@('-jar',$artifact,'--spring.main.web-application-type=none','--spring.profiles.active=rag-admin',
  '--smarttrip.rag.admin-enabled=true',('--smarttrip.rag.command='+$Command),
  '--smarttrip.rag.enabled=false','--smarttrip.rag.evaluate=false','--ai.llm.enabled=false')
 if($Command -in @('inspect','import','rebuild')){$arguments+=('--smarttrip.rag.manifest='+$Manifest)}
 if($Command -eq 'delete'){$arguments+=('--smarttrip.rag.document-id='+$DocumentId)}
 Push-Location -LiteralPath (Join-Path $projectRoot 'smart-trip-backend')
 try { & $Java @arguments; if($LASTEXITCODE -ne 0){throw 'Knowledge command failed; inspect the sanitized local error code'} }
 finally { Pop-Location }
}finally{$env:SMARTTRIP_CONFIG=$previousConfig}