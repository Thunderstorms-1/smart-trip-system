#requires -Version 7.0
param([switch]$Integration,[string]$MavenRepository='',[string]$BuildWorkspace='D:/codex/mission/smarttrip-build')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$backendRoot=Join-Path $projectRoot 'smart-trip-backend'
$hashBytes=[Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))
$projectKey=[Convert]::ToHexString($hashBytes).Substring(0,12)
$buildRoot=Join-Path $BuildWorkspace ('smarttrip-build-'+$projectKey)
$aliasRoot=Join-Path $buildRoot 'backend'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
if(-not(Test-Path -LiteralPath $aliasRoot)){New-Item -ItemType Junction -Path $aliasRoot -Target $backendRoot | Out-Null}
$link=Get-Item -LiteralPath $aliasRoot
if(-not($link.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $link.Target -ne $backendRoot){throw 'Build alias target does not match this project'}
$settings=Join-Path $PSScriptRoot 'maven-settings.xml'
$arguments=@('-s',$settings,'-gs',$settings,('-Dsmarttrip.build-dir='+(Join-Path $buildRoot 'output').Replace('\','/')))
if($MavenRepository){$arguments+=('-Dmaven.repo.local='+$MavenRepository)}
if($Integration){$arguments+='test'}else{$arguments+=@('-DskipTests','package')}
$previousConfig=$env:SMARTTRIP_CONFIG
$previousIntegration=$env:SMARTTRIP_INTEGRATION
try{
 $env:SMARTTRIP_CONFIG=(Join-Path $projectRoot '.local/application-local.yml').Replace('\','/')
 $env:SMARTTRIP_INTEGRATION=if($Integration){'true'}else{'false'}
 Push-Location -LiteralPath $aliasRoot
 & mvn.cmd @arguments
 if($LASTEXITCODE -ne 0){throw 'Backend build or tests failed'}
 if(-not $Integration){$targetRoot=Join-Path $backendRoot 'target';New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null;Copy-Item -LiteralPath (Join-Path $buildRoot 'output/smart-trip-backend-0.0.1-SNAPSHOT.jar') -Destination $targetRoot -Force}
}finally{Pop-Location;$env:SMARTTRIP_CONFIG=$previousConfig;$env:SMARTTRIP_INTEGRATION=$previousIntegration}
