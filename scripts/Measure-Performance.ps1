#requires -Version 7.0
param(
 [ValidateRange(5,100)][int]$Samples=20,
 [int[]]$Sizes=@(100,1000,10000),
 [string]$Reference='',
 [ValidateRange(1.0,10.0)][double]$WarningRatio=1.5,
 [string]$BuildWorkspace='D:/codex/mission/smarttrip-build'
)
$ErrorActionPreference='Stop'
if($Reference -and -not(Test-Path -LiteralPath $Reference)){throw 'Reference baseline does not exist'}
$projectRoot=Split-Path $PSScriptRoot -Parent
if(-not $Reference){
 $defaultReference=Join-Path $projectRoot 'smart-trip-backend/src/test/resources/performance/reference.json'
 if(Test-Path -LiteralPath $defaultReference){$Reference=$defaultReference}
}
for($n=0;$n -lt $Sizes.Count;$n++){
 if($Sizes[$n] -lt 40 -or $Sizes[$n] -gt 10000 -or ($n -gt 0 -and $Sizes[$n] -le $Sizes[$n-1])){throw 'Sizes must increase and stay within 40..10000'}
}
$hash=[Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))
$buildRoot=Join-Path $BuildWorkspace ('smarttrip-build-'+[Convert]::ToHexString($hash).Substring(0,12))
$backendRoot=Join-Path $projectRoot 'smart-trip-backend'
$aliasRoot=Join-Path $buildRoot 'backend'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
if(-not(Test-Path -LiteralPath $aliasRoot)){New-Item -ItemType Junction -Path $aliasRoot -Target $backendRoot | Out-Null}
$link=Get-Item -LiteralPath $aliasRoot
if(-not($link.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $link.Target -ne $backendRoot){throw 'Build alias target does not match this project'}
$reportRoot=Join-Path $projectRoot '.local/performance'
New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null
$report=Join-Path $reportRoot ('baseline-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'.json')
$settings=Join-Path $PSScriptRoot 'maven-settings.xml'
$revision=& git -C $projectRoot rev-parse HEAD
if($LASTEXITCODE -ne 0){throw 'Cannot identify production revision'}
$previousConfig=$env:SMARTTRIP_CONFIG
$previousPerformance=$env:SMARTTRIP_PERFORMANCE
try{
 $env:SMARTTRIP_CONFIG=(Join-Path $projectRoot '.local/application-local.yml').Replace('\','/')
 $env:SMARTTRIP_PERFORMANCE='true'
 Push-Location -LiteralPath $aliasRoot
 & mvn.cmd -s $settings -gs $settings ('-Dsmarttrip.build-dir='+(Join-Path $buildRoot 'output').Replace('\','/')) '-Dtest=PerformanceBaselineTests' ('-Dsmarttrip.baseline.samples='+$Samples) ('-Dsmarttrip.baseline.sizes='+($Sizes -join ',')) ('-Dsmarttrip.baseline.output='+$report.Replace('\','/')) ('-Dsmarttrip.baseline.revision='+$revision) test
 if($LASTEXITCODE -ne 0){throw 'Performance baseline or correctness checks failed'}
}finally{
 Pop-Location
 $env:SMARTTRIP_CONFIG=$previousConfig
 $env:SMARTTRIP_PERFORMANCE=$previousPerformance
}
$current=Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
$current.cases | Select-Object name,fixturePlans,httpP50Ms,httpP95Ms,jdbcP95Ms,responseBytes,resultRows | Format-Table -AutoSize
Write-Output ('Baseline artifact: '+$report)
if($Reference){
 & (Join-Path $PSScriptRoot 'Compare-Performance.ps1') -Current $report -Reference $Reference -WarningRatio $WarningRatio
}