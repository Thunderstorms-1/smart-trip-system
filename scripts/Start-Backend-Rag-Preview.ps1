#requires -Version 5.1
[CmdletBinding()]
param([switch]$CheckOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localConfig = Join-Path $projectRoot '.local/application-local.yml'
$compose = Join-Path $projectRoot 'compose.rag.yaml'
$aliasName = 'smarttrip-travel-v1'
$esUrl = 'http://127.0.0.1:9200'
$ollamaUrl = 'http://127.0.0.1:11434'
$previousEnvironment = @{}
$headers = @{}
$password = $null
$containerEnvironment = $null
$jsonConfig = $null

function Set-PreviewEnvironment([string]$Name, [string]$Value) {
  if (-not $previousEnvironment.ContainsKey($Name)) {
    $previousEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
  }
  [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Assert-PreviewPortFree {
  $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
  if (@($listeners | Where-Object Port -eq 8080).Count) {
    throw '8080 已被占用。请先在 IntelliJ 中停止现有 Spring Boot 进程；若为其他程序，请自行确认并停止。本脚本不会结束任何进程。'
  }
}
function ConvertTo-PreviewNativeArgument([string]$Argument) {
  # Windows command-line quoting; ProcessStartInfo.ArgumentList is not available on .NET Framework.
  return '"' + (($Argument -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Invoke-DockerRead([string[]]$Arguments) {
  # Read UTF-8 directly instead of using the console/OEM code page (important for non-ASCII secrets).
  # Capture both streams in memory; native stderr must never become a PowerShell error record.
  $start = New-Object System.Diagnostics.ProcessStartInfo
  $start.FileName = $dockerPath
  $start.Arguments = (($Arguments | ForEach-Object { ConvertTo-PreviewNativeArgument $_ }) -join ' ')
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $start.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
  $start.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
  $process = New-Object System.Diagnostics.Process
  $process.StartInfo = $start
  try {
    if (-not $process.Start()) { throw 'Native command did not start' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $captured = $stdout.GetAwaiter().GetResult()
    $discardedError = $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw 'Native command failed' }
    return $captured
  } catch {
    throw 'Docker 只读查询失败，请检查引擎和现有 Elasticsearch 容器。未执行启动、重置或修改；原始输出未显示。'
  } finally {
    $process.Dispose()
    $discardedError = $null
  }
}
function Read-PreviewJson([string]$Uri, [hashtable]$RequestHeaders = @{}, [string]$Body = '') {
  try {
    $request = @{ Uri=$Uri; Headers=$RequestHeaders; TimeoutSec=15; ErrorAction='Stop' }
    if ($Body) { $request.Method='POST'; $request.ContentType='application/json'; $request.Body=$Body }
    return Invoke-RestMethod @request
  } catch {
    # Do not echo the request, headers, body or raw exception (which may contain credentials).
    $httpStatus = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($httpStatus -in 401,403) { throw '现有 Elasticsearch 凭据未通过鉴权，未重置密码；请确认运行中的原容器。' }
    throw ('本机 RAG 服务读取失败（HTTP {0}）。请检查 Elasticsearch/Ollama 服务状态；请求详情及凭据未输出。' -f $httpStatus)
  }
}
function ConvertTo-PreviewHashtable([AllowNull()][object]$Value) {
  # ConvertFrom-Json -AsHashtable requires PowerShell 6+. Recurse without flattening JSON arrays.
  if ($null -eq $Value) { return $null }
  if ($Value -is [System.Collections.IDictionary]) {
    $result = @{}
    foreach ($key in $Value.Keys) { $result[$key] = ConvertTo-PreviewHashtable $Value[$key] }
    return $result
  }
  if ($Value -is [System.Management.Automation.PSCustomObject]) {
    $result = @{}
    foreach ($property in $Value.PSObject.Properties) { $result[$property.Name] = ConvertTo-PreviewHashtable $property.Value }
    return $result
  }
  if ($Value -is [Array]) {
    $items = New-Object 'System.Collections.Generic.List[object]'
    foreach ($item in $Value) { $items.Add((ConvertTo-PreviewHashtable $item)) }
    return ,($items.ToArray())
  }
  return $Value
}
function Get-ConfigSection([System.Collections.IDictionary]$Root, [string]$Name) {
  if (-not $Root.Contains($Name)) { $Root[$Name] = @{} }
  if ($Root[$Name] -isnot [System.Collections.IDictionary]) { throw '已有 SPRING_APPLICATION_JSON 的配置层级无效；内容未输出。' }
  return $Root[$Name]
}

try {
  if (-not (Test-Path -LiteralPath $localConfig -PathType Leaf)) { throw '缺少 .local/application-local.yml；请保留并准备原有数据库、JWT、Redis 配置。' }
  if (-not (Test-Path -LiteralPath $compose -PathType Leaf)) { throw '缺少现有 compose.rag.yaml。' }
  if ($CheckOnly) {
    try { Assert-PreviewPortFree } catch { Write-Warning $_.Exception.Message }
  } else { Assert-PreviewPortFree }
  $dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
  $dockerPath = if ($dockerCommand) { $dockerCommand.Source } else {
    @((Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),
      (Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe')) |
      Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
  }
  if (-not $dockerPath) { throw '未找到 Docker 客户端；请先确认现有 Docker Desktop 可用。本脚本不会安装或启动服务。' }

  # Compose validates its required interpolation even for `ps`. This placeholder is discovery-only;
  # it is never sent to ES and cannot alter a container because the only Compose operation is ps.
  Set-PreviewEnvironment 'SMARTTRIP_RAG_ES_PASSWORD' '__compose_ps_read_only_discovery__'
  $containerIds = @((Invoke-DockerRead -Arguments @('compose','-f',$compose,'ps','-q','elasticsearch')) -split '\s+' | Where-Object { $_ })
  if ($containerIds.Count -ne 1 -or $containerIds[0] -notmatch '^[a-fA-F0-9]{12,64}$') {
    throw '现有 compose.rag.yaml 未找到唯一运行中的 Elasticsearch 容器；不会创建或重启容器。'
  }
  try {
    $containerEnvironment = ConvertFrom-Json -InputObject (Invoke-DockerRead -Arguments @('inspect','--format','{{json .Config.Env}}',$containerIds[0]))
  } catch { throw '无法安全读取原容器凭据；容器输出未显示，未更改密码。' }
  $entries = @($containerEnvironment | Where-Object { $_ -is [string] -and $_.StartsWith('ELASTIC_PASSWORD=') })
  if ($entries.Count -ne 1 -or $entries[0].Length -le 'ELASTIC_PASSWORD='.Length) { throw '原容器中没有唯一可用的 ELASTIC_PASSWORD；不会尝试更改密码。' }
  $password = $entries[0].Substring('ELASTIC_PASSWORD='.Length)
  $headers.Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('elastic:' + $password))
  $health = Read-PreviewJson ($esUrl + '/_cluster/health') $headers
  if ($health.timed_out -or $health.status -ne 'green') { throw 'Elasticsearch 集群尚未处于 green 健康状态；请先排查原服务。本脚本不会修改集群。' }
  $aliases = Read-PreviewJson ($esUrl + '/_alias/' + $aliasName) $headers
  $physical = @($aliases.PSObject.Properties.Name)
  if ($physical.Count -ne 1) { throw '正式知识别名缺失或指向多个索引；停止诊断启动，不修改知识库。' }
  $mappingResponse = Read-PreviewJson ($esUrl + '/' + $aliasName + '/_mapping') $headers
  $mapping = $mappingResponse.PSObject.Properties[$physical[0]].Value.mappings
  $dimensions = [int]$mapping.properties.embedding.dims
  if ($mapping._meta.embeddingModel -ne 'bge-m3' -or $mapping._meta.embeddingVersion -ne 'v1' -or
      $dimensions -lt 1 -or $dimensions -ne [int]$mapping._meta.vectorDimensions) {
    throw '正式索引的模型、版本或向量维度映射不一致；未执行重建或导入。'
  }
  $countBody = @{
    size=0; track_total_hits=$true
    query=@{ bool=@{
      filter=@(@{term=@{visibility='PUBLIC'}},@{term=@{authorized=$true}},@{term=@{testOnly=$false}},
        @{term=@{embeddingModel='bge-m3'}},@{term=@{embeddingVersion='v1'}})
      must_not=@(@{exists=@{field='ownerId'}})
    } }
    aggs=@{ documents=@{ cardinality=@{field='documentId'; precision_threshold=40000} } }
  } | ConvertTo-Json -Depth 12 -Compress
  $counts = Read-PreviewJson ($esUrl + '/' + $aliasName + '/_search') $headers $countBody
  if ($counts.timed_out -or $counts._shards.failed -gt 0 -or $counts.hits.total.relation -ne 'eq' -or
      $counts.hits.total.value -lt 1 -or $counts.aggregations.documents.value -lt 1) {
    throw '正式别名没有可确认的公开知识计数，或 ES 响应不完整；未视为空库成功。'
  }
  $tags = Read-PreviewJson ($ollamaUrl + '/api/tags')
  if (-not @($tags.models | Where-Object { $_.name -in 'bge-m3','bge-m3:latest' }).Count) {
    throw 'Ollama 未找到已安装的 bge-m3 模型；本脚本不会下载模型。'
  }

  $values = @{
    SMARTTRIP_CONFIG=$localConfig.Replace('\','/')
    SMARTTRIP_RAG_ENABLED='true'; SMARTTRIP_RAG_ES_URL=$esUrl; SMARTTRIP_RAG_ES_USERNAME='elastic'
    SMARTTRIP_RAG_ES_PASSWORD=$password; SMARTTRIP_RAG_INDEX=$aliasName
    SMARTTRIP_RAG_EMBEDDING_URL=$ollamaUrl; SMARTTRIP_RAG_EMBEDDING_MODEL='bge-m3'; SMARTTRIP_RAG_EMBEDDING_VERSION='v1'
    SMARTTRIP_RAG_RERANK_ENABLED='false'; SMARTTRIP_RAG_EMBEDDING_TIMEOUT_MS='60000'; SMARTTRIP_RAG_RETRIEVAL_TIMEOUT_MS='90000'
    SMARTTRIP_AI_ENABLED='false'; SMARTTRIP_AI_FREE_QUOTA_VERIFIED='false'
  }
  foreach ($name in $values.Keys) { Set-PreviewEnvironment $name $values[$name] }
  # Environment aliases alone cannot override literal values in the existing local YAML.
  # Preserve every unrelated JSON setting, including database/JWT/Redis; only override diagnostic settings.
  $existingJson = [Environment]::GetEnvironmentVariable('SPRING_APPLICATION_JSON','Process')
  $jsonConfig = if ($existingJson) {
    try {
      if (-not $existingJson.TrimStart().StartsWith('{')) { throw 'Expected a JSON object' }
      ConvertTo-PreviewHashtable (ConvertFrom-Json -InputObject $existingJson)
    } catch { throw 'SPRING_APPLICATION_JSON 无效；内容未输出。' }
  } else { @{} }
  if ($jsonConfig -isnot [System.Collections.IDictionary]) { throw 'SPRING_APPLICATION_JSON 必须为对象；内容未输出。' }
  $spring = Get-ConfigSection $jsonConfig 'spring'
  (Get-ConfigSection $spring 'flyway')['enabled'] = $false
  $llm = Get-ConfigSection (Get-ConfigSection $jsonConfig 'ai') 'llm'
  $llm['enabled']=$false; $llm['free-quota-verified']=$false
  $rag = Get-ConfigSection (Get-ConfigSection $jsonConfig 'smarttrip') 'rag'
  $ragOverrides = @{
    enabled=$true; 'elasticsearch-url'=$esUrl; 'elasticsearch-username'='elastic'; 'elasticsearch-password'=$password
    'index-name'=$aliasName; 'embedding-base-url'=$ollamaUrl; 'embedding-model'='bge-m3'; 'embedding-version'='v1'
    'rerank-enabled'=$false; 'admin-enabled'=$false; evaluate=$false; 'preview-only'=$true
    'embedding-timeout-millis'=60000; 'retrieval-timeout-millis'=90000
  }
  foreach ($name in $ragOverrides.Keys) { $rag[$name]=$ragOverrides[$name] }
  Set-PreviewEnvironment 'SPRING_APPLICATION_JSON' (ConvertTo-Json -InputObject $jsonConfig -Depth 32 -Compress)
  Write-Host ('RAG 预检通过：ES green，别名 {0}，公开文档约 {1} 份、切片 {2} 个，bge-m3/v1，映射 {3} 维。' -f
    $aliasName,$counts.aggregations.documents.value,$counts.hits.total.value,$dimensions)
  Write-Host '仅只读 RAG 联调：付费模型、Rerank、Flyway、知识管理和启动任务恢复均关闭；不会生成路书或扣除生成额度。'
  if ($CheckOnly) { Write-Host '检查完成，未构建或启动后端。'; return }
  foreach ($tool in 'mvn.cmd','java') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw ('未找到 {0}，请确认现有 Maven/JDK 的 PATH。' -f $tool) }
  }
  & (Join-Path $PSScriptRoot 'Build-Backend.ps1')
  $jar = Join-Path $projectRoot 'smart-trip-backend/target/smart-trip-backend-0.0.1-SNAPSHOT.jar'
  if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw '最新后端 JAR 不存在，未启动旧产物。' }
  Assert-PreviewPortFree
  Write-Host '启动本机后端 http://127.0.0.1:8080；在已运行的 Vite 开发站点访问 /__dev/rag（需原有登录）。Ctrl+C 停止。'
  & java -jar $jar '--server.address=127.0.0.1' '--server.port=8080' '--spring.flyway.enabled=false' `
    '--ai.llm.enabled=false' '--ai.llm.free-quota-verified=false' '--smarttrip.rag.enabled=true' `
    '--smarttrip.rag.rerank-enabled=false' '--smarttrip.rag.admin-enabled=false' '--smarttrip.rag.evaluate=false' '--smarttrip.rag.preview-only=true'
  if ($LASTEXITCODE -ne 0) { throw '后端退出异常，请检查本机服务日志；未修改任何密码或配置文件。' }
} finally {
  foreach ($name in $previousEnvironment.Keys) {
    if ($null -eq $previousEnvironment[$name]) {
      Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
    } else {
      [Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name],'Process')
    }
  }
  $headers.Clear(); $password=$null; $containerEnvironment=$null; $entries=$null; $jsonConfig=$null; $existingJson=$null
}
