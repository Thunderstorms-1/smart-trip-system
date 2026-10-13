#requires -Version 7.0
param([Parameter(Mandatory)][string]$Current,[Parameter(Mandatory)][string]$Reference,[ValidateRange(1.0,10.0)][double]$WarningRatio=1.5)
$ErrorActionPreference='Stop'
$currentReport=Get-Content -LiteralPath $Current -Raw | ConvertFrom-Json
$prior=Get-Content -LiteralPath $Reference -Raw | ConvertFrom-Json
if($currentReport.schemaVersion -ne 1 -or $prior.schemaVersion -ne 1){throw 'Unsupported baseline schema'}
$currentNames=@($currentReport.environment.PSObject.Properties.Name | Sort-Object)
$priorNames=@($prior.environment.PSObject.Properties.Name | Sort-Object)
$compatible=($currentNames -join ',') -eq ($priorNames -join ',')
foreach($name in $currentNames){if($currentReport.environment.$name -ne $prior.environment.$name){$compatible=$false}}
if(-not $compatible){Write-Warning 'Environment changed; elapsed-time ratios are informational.'}
$rows=@(foreach($case in $currentReport.cases){
 $old=$prior.cases | Where-Object {$_.name -eq $case.name -and $_.fixturePlans -eq $case.fixturePlans} | Select-Object -First 1
 if($old -and $old.httpP95Ms -gt 0){
  $ratio=$case.httpP95Ms/$old.httpP95Ms
  [pscustomobject]@{name=$case.name;fixturePlans=$case.fixturePlans;previousP95Ms=$old.httpP95Ms;currentP95Ms=$case.httpP95Ms;ratio=$ratio;responseBytes=$case.responseBytes;previousResponseBytes=$old.responseBytes;reviewNeeded=$compatible -and $ratio -gt $WarningRatio}
 }
})
[ordered]@{schemaVersion=1;environmentCompatible=$compatible;warningRatio=$WarningRatio;comparisons=$rows} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath ($Current+'.comparison.json') -Encoding utf8
$rows | Format-Table -AutoSize
Write-Output ('Comparison artifact: '+$Current+'.comparison.json')
