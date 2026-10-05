// One explicitly opted-in request. Never prints the API key, prompt or provider body.
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..');
const args=process.argv.slice(2);
const base=args[args.indexOf('--base-url')+1];
const allowed=new Set(['https://maas.qianwenaiapi.com/compatible-mode/v1','https://dashscope.aliyuncs.com/compatible-mode/v1']);
if(!args.includes('--confirm-free-tier-only')||!allowed.has(base)) {
  console.error('No request made. Confirm the matching account, free allowance and active stop-when-used-up protection, then specify an allowed --base-url.');process.exit(2);
}
const evidence={providerHost:new URL(base).hostname,model:'qwen3.8-flash',maxOutputTokens:64,attempts:0,status:'NOT_STARTED',quotaProtection:'user-confirmed',usage:null};
function localKey() {
  if(process.env.SMARTTRIP_AI_KEY)return process.env.SMARTTRIP_AI_KEY;
  const file=fs.readFileSync(path.join(root,'.local/application-local.yml'),'utf8');
  const scope=file.match(/^ai:\s*\r?\n([\s\S]*?)(?=^\S|$(?![\s\S]))/m)?.[1];
  const raw=scope?.match(/^\s+api-key:[ \t]*(.*)$/m)?.[1];
  const value=raw?.split(/\s+#/)[0].trim();
  if(!value)return '';
  if(value.startsWith("'")&&value.endsWith("'"))return value.slice(1,-1).replace(/''/g,"'");
  if(value.startsWith('"')&&value.endsWith('"'))return JSON.parse(value);
  return value;
}
(async()=>{
  const key=localKey();if(!/^[A-Za-z0-9._~+/=-]{16,1024}$/.test(key))throw Error('CONFIG_KEY_UNAVAILABLE');
  const start=Date.now();evidence.attempts=1;
  const response=await fetch(base+'/chat/completions',{
    method:'POST',redirect:'error',signal:AbortSignal.timeout(30000),
    headers:{'Content-Type':'application/json',Authorization:'Bearer '+key},
    body:JSON.stringify({model:evidence.model,stream:true,enable_thinking:false,max_tokens:64,response_format:{type:'json_object'},stream_options:{include_usage:true},messages:[{role:'system',content:'Return only a JSON object.'},{role:'user',content:'Return {"ready":true}.'}]})
  });
  evidence.httpStatus=response.status;
  const text=await response.text();if(text.length>20000)throw Error('RESPONSE_LIMIT');
  if(!response.ok){let code='';try{code=JSON.parse(text)?.error?.code||'';}catch{} evidence.providerCode=/^[A-Za-z0-9_.-]{1,100}$/.test(code)?code:'UNKNOWN';throw Error('PROVIDER_REJECTED');}
  let output='',ended=false,stopped=false;
  for(const line of text.split(/\r?\n/)){
    if(!line.startsWith('data:'))continue;
    const data=line.slice(5).trim();if(data==='[DONE]'){ended=true;break;}
    const chunk=JSON.parse(data);if(chunk.error)throw Error('STREAM_REJECTED');
    if(chunk.usage)evidence.usage=Object.fromEntries(['prompt_tokens','completion_tokens','total_tokens'].filter(k=>Number.isSafeInteger(chunk.usage[k])&&chunk.usage[k]>=0).map(k=>[k,chunk.usage[k]]));
    for(const c of chunk.choices||[]){output+=c.delta?.content||'';if(c.finish_reason==='stop')stopped=true;else if(c.finish_reason!=null)throw Error('INCOMPLETE_OUTPUT');}
  }
  if(!ended||!stopped||JSON.parse(output).ready!==true)throw Error('INVALID_COMPLETION');
  evidence.status='PASSED';evidence.durationMs=Date.now()-start;
})().catch(e=>{if(/^[A-Z0-9_]{1,40}$/.test(e.cause?.code||''))evidence.networkCode=e.cause.code;evidence.status='FAILED';evidence.failure=['CONFIG_KEY_UNAVAILABLE','PROVIDER_REJECTED','STREAM_REJECTED','INCOMPLETE_OUTPUT','INVALID_COMPLETION','RESPONSE_LIMIT'].includes(e.message)?e.message:e.name==='TimeoutError'?'TIMEOUT':'CONNECTION_OR_FORMAT_ERROR';process.exitCode=1;}).finally(()=>{
  try{fs.mkdirSync(path.join(root,'.local'),{recursive:true});fs.writeFileSync(path.join(root,'.local/provider-probe.json'),JSON.stringify(evidence,null,2));}catch{}
  console.log(JSON.stringify(evidence));
});
