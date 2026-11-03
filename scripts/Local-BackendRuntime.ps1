#requires -Version 5.1
# Runtime helpers only. No Java business changes, knowledge mutations or model calls.
function ConvertTo-LocalNativeArgument([string]$Argument) {
  return '"' + (($Argument -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Invoke-LocalNative([string]$Executable,[string[]]$Arguments) {
  $start=New-Object Diagnostics.ProcessStartInfo
  $start.FileName=$Executable
  $start.Arguments=(($Arguments|ForEach-Object { ConvertTo-LocalNativeArgument $_ }) -join ' ')
  $start.UseShellExecute=$false;$start.CreateNoWindow=$true
  $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
  $start.StandardOutputEncoding=New-Object Text.UTF8Encoding($false)
  $start.StandardErrorEncoding=New-Object Text.UTF8Encoding($false)
  $process=New-Object Diagnostics.Process;$process.StartInfo=$start
  try{
    if(-not $process.Start()){throw 'Native startup failed'}
    $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
    $process.WaitForExit();$output=$stdout.GetAwaiter().GetResult();$discard=$stderr.GetAwaiter().GetResult()
    if($process.ExitCode -ne 0){throw 'Native command failed'}
    return $output.Trim()
  }catch{throw '本机依赖命令失败；原始输出未显示，未更改任何密码。请检查 Docker 引擎及对应服务。'}
  finally{$process.Dispose();$discard=$null}
}
function ConvertTo-LocalHashtable([AllowNull()][object]$Value) {
  if($null -eq $Value){return $null}
  if($Value -is [Collections.IDictionary]){$r=@{};foreach($key in $Value.Keys){$r[$key]=ConvertTo-LocalHashtable $Value[$key]};return $r}
  if($Value -is [Management.Automation.PSCustomObject]){$r=@{};foreach($p in $Value.PSObject.Properties){$r[$p.Name]=ConvertTo-LocalHashtable $p.Value};return $r}
  if($Value -is [Array]){$r=New-Object 'Collections.Generic.List[object]';foreach($v in $Value){$r.Add((ConvertTo-LocalHashtable $v))};return ,($r.ToArray())}
  return $Value
}
function Get-LocalSection([Collections.IDictionary]$Root,[string]$Name) {
  if(-not $Root.Contains($Name)){$Root[$Name]=@{}}
  if($Root[$Name] -isnot [Collections.IDictionary]){throw '已有进程 JSON 配置层级无效，未输出内容。'}
  return $Root[$Name]
}
function Set-LocalEnvironment([hashtable]$Saved,[string]$Name,[AllowNull()][string]$Value) {
  if(-not $Saved.ContainsKey($Name)){$Saved[$Name]=[Environment]::GetEnvironmentVariable($Name,'Process')}
  [Environment]::SetEnvironmentVariable($Name,$Value,'Process')
}
function Restore-LocalEnvironment([hashtable]$Saved) {
  foreach($name in $Saved.Keys){
    if($null -eq $Saved[$name]){Remove-Item -LiteralPath ('Env:\'+$name) -ErrorAction SilentlyContinue}
    else{[Environment]::SetEnvironmentVariable($name,$Saved[$name],'Process')}
  }
}
function Get-LocalDocker {
  $command=Get-Command docker.exe -ErrorAction SilentlyContinue
  if($command){return $command.Source}
  foreach($path in @((Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),(Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe'))){
    if(Test-Path -LiteralPath $path -PathType Leaf){return $path}
  }
  throw '找不到 Docker 客户端，请先确认已有 Docker Desktop 可用。'
}
function Test-LocalTcp([int]$Port) {
  $client=New-Object Net.Sockets.TcpClient
  try{$task=$client.ConnectAsync('127.0.0.1',$Port);return ($task.Wait(1500) -and $client.Connected)}catch{return $false}finally{$client.Dispose()}
}
function Assert-LocalBackendPort([int]$Port) {
  if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object Port -eq $Port).Count){
    throw ('{0} 已被占用。请先停止 IntelliJ 中原 Spring Boot 或自行确认占用程序；脚本不会结束现有进程。' -f $Port)
  }
}
function Read-LocalJson([string]$Uri,[hashtable]$Headers=@{},[string]$Body='') {
  try{
    $request=@{Uri=$Uri;Headers=$Headers;TimeoutSec=15;ErrorAction='Stop'}
    if($Body){$request.Method='POST';$request.ContentType='application/json';$request.Body=$Body}
    return Invoke-RestMethod @request
  }catch{
    $status=if($_.Exception.Response){[int]$_.Exception.Response.StatusCode}else{0}
    throw ('本机 ES/Ollama 预检失败（HTTP {0}），未输出请求、凭据或原始响应。' -f $status)
  }
}
function Get-LocalKnowledgeRuntime([string]$Docker,[string]$ProjectRoot) {
  $saved=@{};$headers=@{}
  try{
    Set-LocalEnvironment $saved 'SMARTTRIP_RAG_ES_PASSWORD' '__compose_ps_discovery_only__'
    $ids=@((Invoke-LocalNative $Docker @('compose','-f',(Join-Path $ProjectRoot 'compose.rag.yaml'),'ps','-q','elasticsearch')) -split '\s+'|Where-Object {$_})
    if($ids.Count -ne 1 -or $ids[0] -notmatch '^[a-fA-F0-9]{12,64}$'){throw '没有唯一运行中的 RAG Elasticsearch 容器；未重启或重建现有服务。'}
    try{
      $variables=ConvertFrom-Json -InputObject (Invoke-LocalNative $Docker @('inspect','--format','{{json .Config.Env}}',$ids[0]))
      $entries=@($variables|Where-Object {$_ -is [string] -and $_.StartsWith('ELASTIC_PASSWORD=')})
      if($entries.Count -ne 1){throw 'Missing credential'}
      $password=$entries[0].Substring('ELASTIC_PASSWORD='.Length)
      if(-not $password){throw 'Empty credential'}
    }catch{throw '无法安全读取现有 ES 容器环境凭据；未输出内容或重置密码。'}
    $ports=ConvertFrom-Json -InputObject (Invoke-LocalNative $Docker @('inspect','--format','{{json .NetworkSettings.Ports}}',$ids[0]))
    $bindings=@($ports.'9200/tcp')
    if($bindings.Count -ne 1 -or $bindings[0].HostIp -ne '127.0.0.1' -or $bindings[0].HostPort -ne '9200'){throw '现有 ES 未按预期绑定本机 127.0.0.1:9200，未修改端口。'}
    $headers.Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('elastic:'+$password))
    $url='http://127.0.0.1:9200';$alias='smarttrip-travel-v1'
    $health=Read-LocalJson ($url+'/_cluster/health') $headers
    if($health.status -ne 'green' -or $health.timed_out){throw 'ES 集群尚未 green，未自动修改集群。'}
    $aliases=Read-LocalJson ($url+'/_alias/'+$alias) $headers;$physical=@($aliases.PSObject.Properties.Name)
    if($physical.Count -ne 1){throw '正式知识别名缺失或不唯一，未执行知识管理。'}
    $response=Read-LocalJson ($url+'/'+$alias+'/_mapping') $headers
    $mapping=$response.PSObject.Properties[$physical[0]].Value.mappings
    $dimensions=[int]$mapping.properties.embedding.dims
    if($mapping._meta.embeddingModel -ne 'bge-m3' -or $mapping._meta.embeddingVersion -ne 'v1' -or $dimensions -lt 1 -or $dimensions -ne [int]$mapping._meta.vectorDimensions){throw 'ES 模型版本或维度映射不一致。'}
    $count=Read-LocalJson ($url+'/'+$alias+'/_count') $headers
    if($count.count -lt 1){throw '正式知识库为空，未自动导入。'}
    $tags=Read-LocalJson 'http://127.0.0.1:11434/api/tags'
    if(-not @($tags.models|Where-Object {$_.name -in 'bge-m3','bge-m3:latest'}).Count){throw '未找到已安装的 BGE-M3，未下载模型。'}
    return @{Password=$password;Alias=$alias;PhysicalIndex=$physical[0];Chunks=$count.count;Dimensions=$dimensions}
  }finally{Restore-LocalEnvironment $saved;$headers.Clear();$variables=$null;$entries=$null;$password=$null}
}
function Test-LocalRedis {
  $client=New-Object Net.Sockets.TcpClient
  try{
    if(-not $client.ConnectAsync('127.0.0.1',6379).Wait(1500)){return $false}
    $client.ReceiveTimeout=1500;$client.SendTimeout=1500
    $stream=$client.GetStream();$bytes=[Text.Encoding]::ASCII.GetBytes("*1`r`n"+'$4'+"`r`nPING`r`n")
    $stream.Write($bytes,0,$bytes.Length);$reader=New-Object IO.StreamReader($stream)
    return ($reader.ReadLine() -eq '+PONG')
  }catch{return $false}finally{$client.Dispose()}
}
function Ensure-LocalDevelopmentRedis([string]$Docker,[string]$ProjectRoot,[switch]$CheckOnly) {
  $sha=[Security.Cryptography.SHA256]::Create()
  try{$key=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($ProjectRoot))).Replace('-','')).Substring(0,12).ToLowerInvariant()}finally{$sha.Dispose()}
  $name='smarttrip-jvm-redis-'+$key;$volume=$name+'-data';$label='com.smarttrip.jvm-project'
  $ids=@((Invoke-LocalNative $Docker @('ps','-a','-q','--filter',('name=^/'+$name+'$'))) -split '\s+'|Where-Object {$_})
  if($ids.Count -gt 1){throw '本机 Redis 管理对象不唯一。'}
  if($ids.Count -eq 1){
    $labels=ConvertFrom-Json -InputObject (Invoke-LocalNative $Docker @('inspect','--format','{{json .Config.Labels}}',$ids[0]))
    if($labels.PSObject.Properties[$label].Value -ne $key){throw '同名 Redis 不属于此项目，拒绝修改。'}
    $ports=ConvertFrom-Json -InputObject (Invoke-LocalNative $Docker @('inspect','--format','{{json .HostConfig.PortBindings}}',$ids[0]))
    $bindings=@($ports.'6379/tcp')
    if($bindings.Count -ne 1 -or $bindings[0].HostIp -ne '127.0.0.1' -or $bindings[0].HostPort -ne '6379'){throw '已管理 Redis 的端口绑定不安全，拒绝更改。'}
    $mounts=@(ConvertFrom-Json -InputObject (Invoke-LocalNative $Docker @('inspect','--format','{{json .Mounts}}',$ids[0])))
    if(-not @($mounts|Where-Object {$_.Type -eq 'volume' -and $_.Name -eq $volume -and $_.Destination -eq '/data'}).Count){throw 'Redis 数据卷与项目专用卷不一致，拒绝更改。'}
  }
  if(Test-LocalRedis){
    foreach($listener in @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object Port -eq 6379)){
      if(-not [Net.IPAddress]::IsLoopback($listener.Address)){throw '6379 包含非回环监听；脚本不会修改现有服务。'}
    }
    return @{Name=$name;Volume=$volume;Status='READY';Managed=($ids.Count -eq 1)}
  }
  if(Test-LocalTcp 6379){throw '6379 已被其他程序或需认证的 Redis 占用，未停止进程或覆盖凭据。'}
  if($CheckOnly){return @{Name=$name;Volume=$volume;Status='NOT_RUNNING';Managed=($ids.Count -eq 1)}}
  if($env:SMARTTRIP_REDIS_PASSWORD -or $env:SPRING_DATA_REDIS_PASSWORD){throw '调用进程要求 Redis 密码，不能自动创建无认证实例；请核对已配置的服务。'}
  if($ids.Count -eq 1){$null=Invoke-LocalNative $Docker @('start',$ids[0])}
  else{
    $null=Invoke-LocalNative $Docker @('image','inspect','redis:8-alpine','--format','{{.Id}}')
    $volumes=@((Invoke-LocalNative $Docker @('volume','ls','--format','{{.Name}}')) -split '\r?\n')
    if($volumes -contains $volume){
      $labels=ConvertFrom-Json -InputObject (Invoke-LocalNative $Docker @('volume','inspect','--format','{{json .Labels}}',$volume))
      if($labels.PSObject.Properties[$label].Value -ne $key){throw '同名数据卷不属于此项目，拒绝使用。'}
    }else{$null=Invoke-LocalNative $Docker @('volume','create','--label',($label+'='+$key),$volume)}
    $null=Invoke-LocalNative $Docker @('run','--pull','never','--detach','--name',$name,'--label',($label+'='+$key),
      '--publish','127.0.0.1:6379:6379','--mount',('type=volume,source='+$volume+',target=/data'),
      '--restart','unless-stopped','--memory','256m','redis:8-alpine','redis-server','--appendonly','yes','--bind','0.0.0.0')
  }
  for($i=0;$i -lt 40;$i++){if(Test-LocalRedis){return @{Name=$name;Volume=$volume;Status='READY';Managed=$true}};Start-Sleep -Milliseconds 250}
  throw '独立开发 Redis 尚未就绪；已保留其专用数据卷，未重建或清空任何实例。'
}
