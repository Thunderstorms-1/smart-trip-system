#requires -Version 5.1
[CmdletBinding()]
param([ValidateRange(1024,65535)][int]$Port=8080,[switch]$CheckOnly)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Local-BackendRuntime.ps1')
$previous=@{};$pushed=$false;$runtime=$null;$configuration=$null
try{
  if(-not(Test-Path -LiteralPath (Join-Path $projectRoot '.local/application-local.yml') -PathType Leaf)){throw '缺少原有 .local/application-local.yml。'}
  if(-not $CheckOnly){Assert-LocalBackendPort $Port}
  $docker=Get-LocalDocker
  $runtime=Get-LocalKnowledgeRuntime $docker $projectRoot
  if(-not(Test-LocalTcp 3306)){throw '本机 MySQL 127.0.0.1:3306 不可达，未启动或更改数据库。'}
  $redis=Ensure-LocalDevelopmentRedis $docker $projectRoot -CheckOnly:$CheckOnly

  $existing=[Environment]::GetEnvironmentVariable('SPRING_APPLICATION_JSON','Process')
  $configuration=if($existing){
    try{if(-not $existing.TrimStart().StartsWith('{')){throw 'Expected object'};ConvertTo-LocalHashtable (ConvertFrom-Json $existing)}catch{throw '已有 SPRING_APPLICATION_JSON 无效，未输出内容。'}
  }else{@{}}
  if($configuration -isnot [Collections.IDictionary]){throw 'SPRING_APPLICATION_JSON 必须为对象。'}
  # The normal entry does not pin Flyway to the old controlled-acceptance target.
  $spring=Get-LocalSection $configuration 'spring'
  if($env:SPRING_FLYWAY_TARGET -or ($spring.Contains('flyway') -and $spring.flyway -is [Collections.IDictionary] -and $spring.flyway.Contains('target'))){
    throw '检测到旧验收用 Flyway target；请使用未设置该限制的新终端。普通开发启动不会永久锁定迁移版本。'
  }

  # Retain the existing AI intent. Loading configuration never invokes a ChatModel.
  $aiNames=@{SMARTTRIP_AI_ENABLED='enabled';SMARTTRIP_AI_FREE_QUOTA_VERIFIED='free-quota-verified';SMARTTRIP_AI_KEY='api-key';SMARTTRIP_AI_BASE_URL='base-url';SMARTTRIP_AI_MODEL='model';SMARTTRIP_AI_MAX_OUTPUT_TOKENS='max-output-tokens'}
  $dotenv=Join-Path $projectRoot '.env'
  if(Test-Path -LiteralPath $dotenv){foreach($line in [IO.File]::ReadAllLines($dotenv)){
    if($line -notmatch '^\s*(SMARTTRIP_AI_[A-Z_]+)\s*=\s*(.*?)\s*$'){continue}
    $name=$Matches[1];$value=$Matches[2]
    if(-not $aiNames.ContainsKey($name) -or $null -ne [Environment]::GetEnvironmentVariable($name,'Process')){continue}
    if($value.StartsWith('"') -or $value.StartsWith("'")){
      $close=$value.IndexOf($value[0],1)
      if($close -lt 1 -or $value.Substring($close+1).Trim() -notmatch '^(?:#.*)?$'){throw ($name+' 引号无效，未输出值。')}
      $value=$value.Substring(1,$close-1)
    }else{$value=($value -split '\s+#',2)[0].Trim()}
    Set-LocalEnvironment $previous $name $value
  }}
  $llm=Get-LocalSection (Get-LocalSection $configuration 'ai') 'llm'
  foreach($name in $aiNames.Keys){
    $value=[Environment]::GetEnvironmentVariable($name,'Process');if($null -eq $value){continue}
    if($name -in 'SMARTTRIP_AI_ENABLED','SMARTTRIP_AI_FREE_QUOTA_VERIFIED'){
      $flag=$false;if(-not [bool]::TryParse($value,[ref]$flag)){throw ($name+' 必须是 true/false。')};$llm[$aiNames[$name]]=$flag
    }elseif($name -eq 'SMARTTRIP_AI_MAX_OUTPUT_TOKENS'){
      $limit=0;if(-not [int]::TryParse($value,[ref]$limit)){throw 'AI 输出长度必须是整数。'};$llm[$aiNames[$name]]=$limit
    }else{$llm[$aiNames[$name]]=$value}
  }
  # Explicit process DB overrides are useful for isolated verification; absent overrides preserve local YAML.
  $dbNames=@{SMARTTRIP_DB_URL='url';SMARTTRIP_DB_USER='username';SMARTTRIP_DB_PASSWORD='password'}
  foreach($name in $dbNames.Keys){$value=[Environment]::GetEnvironmentVariable($name,'Process');if($null -ne $value){(Get-LocalSection $spring 'datasource')[$dbNames[$name]]=$value}}
  $redisProperties=Get-LocalSection (Get-LocalSection $spring 'data') 'redis'
  $redisProperties['host']='127.0.0.1';$redisProperties['port']=6379
  if($env:SMARTTRIP_REDIS_DATABASE){$database=0;if(-not [int]::TryParse($env:SMARTTRIP_REDIS_DATABASE,[ref]$database) -or $database -lt 0 -or $database -gt 15){throw 'Redis database 必须为 0 至 15。'};$redisProperties['database']=$database}
  $rag=Get-LocalSection (Get-LocalSection $configuration 'smarttrip') 'rag'
  $ragSettings=@{enabled=$true;'elasticsearch-url'='http://127.0.0.1:9200';'elasticsearch-username'='elastic';'elasticsearch-password'=$runtime.Password;
    'index-name'=$runtime.Alias;'embedding-base-url'='http://127.0.0.1:11434';'embedding-model'='bge-m3';'embedding-version'='v1';
    'rerank-enabled'=$false;'admin-enabled'=$false;evaluate=$false;'preview-only'=$false;'embedding-timeout-millis'=60000;'retrieval-timeout-millis'=90000}
  foreach($name in $ragSettings.Keys){$rag[$name]=$ragSettings[$name]}
  $values=@{SMARTTRIP_CONFIG=(Join-Path $projectRoot '.local/application-local.yml').Replace('\','/');SMARTTRIP_RAG_ENABLED='true';
    SMARTTRIP_RAG_ES_URL='http://127.0.0.1:9200';SMARTTRIP_RAG_ES_USERNAME='elastic';SMARTTRIP_RAG_ES_PASSWORD=$runtime.Password;SMARTTRIP_RAG_INDEX=$runtime.Alias;
    SMARTTRIP_RAG_EMBEDDING_URL='http://127.0.0.1:11434';SMARTTRIP_RAG_EMBEDDING_MODEL='bge-m3';SMARTTRIP_RAG_EMBEDDING_VERSION='v1';SMARTTRIP_RAG_RERANK_ENABLED='false';
    SMARTTRIP_REDIS_HOST='127.0.0.1';SMARTTRIP_REDIS_PORT='6379'}
  foreach($name in $values.Keys){Set-LocalEnvironment $previous $name $values[$name]}
  Set-LocalEnvironment $previous 'SPRING_APPLICATION_JSON' (ConvertTo-Json -InputObject $configuration -Depth 32 -Compress)
  Write-Host ('ES green，正式知识 {0} 个切片，bge-m3/v1，{1} 维；Ollama 模型存在。' -f $runtime.Chunks,$runtime.Dimensions)
  Write-Host ('Redis 127.0.0.1:6379：{0}；现有 Docker Redis 与原数据卷保持独立。' -f $redis.Status)
  Write-Host 'ES 沿用原容器密码，仅通过进程 JSON 覆盖实际 Spring 属性；AI 配置沿用既有启用及额度确认，不发起模型请求。'
  if($CheckOnly){Write-Host '只读预检结束；未构建或启动应用，也未创建/启动 Redis。';return}
  foreach($tool in 'mvn.cmd','java'){if(-not(Get-Command $tool -ErrorAction SilentlyContinue)){throw ('找不到 '+$tool+'，请检查既有 JDK/Maven PATH。')}}
  & (Join-Path $PSScriptRoot 'Build-Backend.ps1')
  $jar=Join-Path $projectRoot 'smart-trip-backend/target/smart-trip-backend-0.0.1-SNAPSHOT.jar'
  if(-not(Test-Path -LiteralPath $jar -PathType Leaf)){throw '最新后端构建产物不存在。'}
  Assert-LocalBackendPort $Port
  Push-Location -LiteralPath (Join-Path $projectRoot 'smart-trip-backend');$pushed=$true
  Write-Host ('启动正常开发后端 http://127.0.0.1:{0}；RAG 已开启，Ctrl+C 停止后端，独立开发 Redis 保留供下次复用。' -f $Port)
  & java -jar $jar '--server.address=127.0.0.1' ('--server.port='+$Port) '--smarttrip.rag.enabled=true' '--smarttrip.rag.admin-enabled=false' '--smarttrip.rag.evaluate=false' '--smarttrip.rag.preview-only=false'
  if($LASTEXITCODE -ne 0){throw '后端启动或退出失败；请检查服务日志，未修改密码或运行配置文件。'}
}finally{
  if($pushed){Pop-Location}
  Restore-LocalEnvironment $previous
  if($runtime){$runtime.Password=$null};$configuration=$null;$existing=$null;$value=$null
}
