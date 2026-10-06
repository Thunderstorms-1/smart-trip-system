(() => {
  'use strict';
  const $ = s => document.querySelector(s);
  const esc = text => String(text).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const paths = {pin:'M20 10c0 6-8 11-8 11S4 16 4 10a8 8 0 1 1 16 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0',route:'M6 5a2 2 0 1 0 0 .1M18 19a2 2 0 1 0 0 .1M6 7v5h12v5',food:'M5 3v6m3-6v6M2 3v6a3 3 0 0 0 6 0m-3 3v9M17 3v18m0-18c-4 3-4 8 0 8',leaf:'M19 4C6 2 1 14 7 18c7 4 14-4 12-14ZM5 20 15 10',close:'m6 6 12 12M6 18 18 6',check:'m5 12 4 4 10-10'};
  const icon = n => `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${paths[n]}"/></svg>`;
  let toastTimer;
  const toast = text => { $('#toast').textContent = text; $('#toast').classList.add('visible'); clearTimeout(toastTimer); toastTimer=setTimeout(()=>$('#toast').classList.remove('visible'),3500); };
  if ($('#quick-plan')) {
    $('#quick-plan').addEventListener('submit', e => { e.preventDefault(); if($('#plan-days').value!=='4'){toast('当前原型仅提供 4 天演示路书，请选择 4 天慢游。');return;}location.href='workbench.html?day=2';});
    return;
  }
  if (!$('#timeline-panel')) return;
  const seed = [
    [{name:'古城北门 · 慢慢出发',type:'pin',time:'09:30',duration:90,note:'从古城的街巷开始，留一些自由探索的时间。'},{name:'古城午餐',type:'food',time:'12:00',duration:60,note:'本地演示用餐安排。'},{name:'湖岸散步',type:'leaf',time:'14:00',duration:120,note:'湖岸地点为示意，不提供真实导航。'},{name:'古城夜色',type:'pin',time:'17:30',duration:90,note:'根据实际情况调整。'}],
    [{name:'喜洲古镇',type:'pin',time:'09:30',duration:90,note:'沿古镇走一段，留意路边的小店与田野。'},{name:'周城 · 午餐时间',type:'food',time:'12:00',duration:60,note:'午餐后留一点休息时间。'},{name:'海舌公园',type:'leaf',time:'14:00',duration:120,note:'在湖边散步，不赶时间。地点与路线为演示。'},{name:'洱海 · 等一场日落',type:'leaf',time:'17:30',duration:90,note:'日落时间为静态安排，非真实预报。'}],
    [{name:'双廊 · 湖边清晨',type:'leaf',time:'09:30',duration:90,note:'本地示意安排。'},{name:'双廊午餐',type:'food',time:'12:00',duration:60,note:'给用餐留一点时间。'},{name:'湖岸步道',type:'leaf',time:'14:00',duration:120,note:'不提供实际开放或路线信息。'},{name:'双廊夜景',type:'pin',time:'17:30',duration:90,note:'本地演示安排。'}],
    [{name:'洱海东岸',type:'leaf',time:'09:30',duration:90,note:'沿湖探索，仅作视觉演示。'},{name:'湖边午餐',type:'food',time:'12:00',duration:60,note:'给午餐预留一小时。'},{name:'东岸休息点',type:'leaf',time:'14:00',duration:120,note:'虚构的路线节点，不提供导航。'},{name:'归途 · 回到大理',type:'pin',time:'17:30',duration:90,note:'本地演示收尾节点。'}]
  ];
  let days=structuredClone(seed), day=2, selected=null, zoom=1, lastTrigger=null;
  const queryDay=Number(new URLSearchParams(location.search).get('day')); if(queryDay>=1&&queryDay<=4)day=queryDay;
  const dayNames=['古城，慢慢出发','喜洲，沿湖慢行','双廊，在湖边停留','东岸，留一段归途'];
  const coords=[['38.4%','31.7%','21.8%','22.1%'],['46.9%','56.3%','43.6%','42.8%'],['60%','73.3%','67.9%','42.8%'],['70.1%','50.8%','83.8%','29.3%']];
  const panel=$('#timeline-panel'), sheet=$('#node-sheet');
  const isMobile=()=>matchMedia('(max-width:700px)').matches;
  function render(){
    $('#day-title').textContent=dayNames[day-1]; $('#day-subtitle').textContent=`第 ${day} 天 · 4 个安排`;
    document.querySelectorAll('[data-day]').forEach(b=>{b.classList.toggle('active',Number(b.dataset.day)===day);b.setAttribute('aria-pressed',String(Number(b.dataset.day)===day));});
    $('#timeline-nodes').innerHTML=days[day-1].map((n,i)=>`<button class="timeline-node ${selected===i?'selected':''}" data-node="${i}" aria-label="选择 ${esc(n.name)}" aria-pressed="${selected===i}"><span class="node-time">${esc(n.time)}</span><span class="node-info" data-order="${i+1}"><strong class="node-name">${esc(n.name)}</strong><span class="node-meta">${icon(n.type)}${n.type==='food'?'用餐':'活动'} · ${n.duration} 分钟</span><span class="node-duration">固定演示地点 · 第 ${day} 天</span></span></button>${i<3?`<div class="node-transfer">${icon('route')} 驾车 · ${[18,25,12][i]} 分钟 / 演示</div>`:''}`).join('');
    $('#map-pins').innerHTML=days[day-1].map((n,i)=>`<button class="map-pin ${selected===i?'selected':''}" data-node="${i}" aria-pressed="${selected===i}" aria-label="地图地点 ${esc(n.name)}" style="--x:${coords[i][0]};--y:${coords[i][1]};--mx:${coords[i][2]};--my:${coords[i][3]}"><span class="pin-number">${i+1}</span><span class="pin-label">${esc(n.name)}</span></button>`).join('');
    const label=$('.map-location-label span'); label.innerHTML=`大理 · ${['古城','喜洲','双廊','东岸'][day-1]}<strong>演示路线 / 非真实地图</strong>`;
  }
  function inspector(n,i){return `<div class="inspector-top"><span>第 ${day} 天 · 节点 ${i+1}</span><button class="icon-button" data-close-inspector aria-label="关闭节点编辑">${icon('close')}</button></div><h2 class="inspector-title">${esc(n.name)}</h2><p class="inspector-subtitle">${icon('pin')} 已选中示意地点 · 本地演示</p><div class="inspector-context" aria-label="节点在路线上的示意位置"><svg viewBox="0 0 280 87" aria-hidden="true"><rect width="280" height="87" fill="#eaf0f3"/><path d="M175 0h105v87H160q35-27 9-47T175 0Z" fill="#b9d9e4"/><path d="M0 48h152M76 0v87" fill="none" stroke="#fff" stroke-width="12"/><path d="M40 48h101V18" fill="none" stroke="#e97a44" stroke-width="4"/><circle cx="141" cy="18" r="7" fill="#214b63" stroke="white" stroke-width="3"/></svg><span>示意位置</span></div><form class="inspector-form"><label>地点名称<input name="name" maxlength="48" value="${esc(n.name)}" required></label><div class="field-pair"><label>开始时间<input type="time" name="time" value="${esc(n.time)}" required></label><label>停留时长<select name="duration">${[30,60,90,120,150].map(d=>`<option value="${d}" ${d===n.duration?'selected':''}>${d} 分钟</option>`).join('')}</select></label></div><label>旅行备注<textarea name="note" maxlength="180" rows="2">${esc(n.note)}</textarea></label><button type="submit" class="primary">${icon('check')} 应用到演示行程</button><p class="inspector-static-note">只修改当前页面，刷新即恢复。不会保存路书，也不会重算示意路线或评分。</p></form>`;}
  function selectNode(i,trigger){selected=i;lastTrigger=trigger;$('#insight-panel').classList.remove('mobile-open');render();
    if(isMobile()){sheet.innerHTML=inspector(days[day-1][i],i);sheet.showModal();document.body.style.overflow='hidden';sheet.querySelector('[data-close-inspector]').focus();}
    else{$('#insight-panel').hidden=true;$('#desktop-inspector').hidden=false;$('#desktop-inspector').innerHTML=inspector(days[day-1][i],i);}
  }
  function closeInspector(restore=true){if(sheet.open)sheet.close();$('#desktop-inspector').hidden=true;$('#insight-panel').hidden=false;document.body.style.overflow='';const old=selected;selected=null;render();if(restore&&old!==null){const area=lastTrigger?.classList.contains('map-pin')?'#map-pins':'#timeline-nodes';$(area)?.querySelector(`[data-node="${old}"]`)?.focus();}}
  document.addEventListener('click',e=>{const target=e.target.closest('[data-node]');if(target){selectNode(Number(target.dataset.node),target);return;}if(e.target.closest('[data-close-inspector]'))closeInspector();const date=e.target.closest('[data-day]');if(date){closeInspector(false);day=Number(date.dataset.day);$('#insight-panel').classList.remove('mobile-open');render();}});
  document.addEventListener('submit',e=>{if(!e.target.matches('.inspector-form'))return;e.preventDefault();const form=new FormData(e.target),name=String(form.get('name')||'').trim();if(!name){e.target.elements.name.setCustomValidity('请输入地点名称');e.target.elements.name.reportValidity();return;}days[day-1][selected]={...days[day-1][selected],name,time:form.get('time'),duration:Number(form.get('duration')),note:form.get('note')};closeInspector();$('#local-status').textContent='当前页已修改';toast('已更新本地演示节点，刷新页面即恢复。');});
  document.addEventListener('input',e=>{if(e.target.name==='name')e.target.setCustomValidity('');});
  sheet.addEventListener('cancel',e=>{e.preventDefault();closeInspector();});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!sheet.open){if(selected!==null)closeInspector();$('#insight-panel').classList.remove('mobile-open');}});
  $('#reset-demo').addEventListener('click',()=>{closeInspector(false);days=structuredClone(seed);$('#local-status').textContent='本地演示';zoom=1;$('#map-layer').style.transform='';panel.dataset.sheet='half';$('#insight-panel').classList.remove('mobile-open');render();toast('演示内容已重置。');});
  $('#sheet-cycle').addEventListener('click',()=>{const states=['half','full','collapsed'];panel.dataset.sheet=states[(states.indexOf(panel.dataset.sheet||'half')+1)%3];$('#sheet-cycle').setAttribute('aria-expanded',String(panel.dataset.sheet!=='collapsed'));});
  $('#mobile-insight').addEventListener('click',()=>{$('#insight-panel').classList.add('mobile-open');$('#close-mobile-insight').focus();});
  $('#close-mobile-insight').addEventListener('click',()=>{$('#insight-panel').classList.remove('mobile-open');$('#mobile-insight').focus();});
  $('#show-suggestion').addEventListener('click',()=>{const expanded=$('#suggestion-detail').hidden;$('#suggestion-detail').hidden=!expanded;$('#show-suggestion').setAttribute('aria-expanded',String(expanded));$('#show-suggestion').firstChild.textContent=expanded?'收起演示建议 ':'查看演示建议 ';});
  const updateZoom=()=>{$('#map-layer').style.transform=`scale(${zoom})`;$('#zoom-in').disabled=zoom>=1.4;$('#zoom-out').disabled=zoom<=.9;};
  $('#zoom-in').addEventListener('click',()=>{zoom=Math.min(1.4,+(zoom+.1).toFixed(1));updateZoom();});$('#zoom-out').addEventListener('click',()=>{zoom=Math.max(.9,+(zoom-.1).toFixed(1));updateZoom();});$('#recenter-map').addEventListener('click',()=>{zoom=1;updateZoom();});
  matchMedia('(max-width:700px)').addEventListener('change',()=>{closeInspector(false);$('#insight-panel').classList.remove('mobile-open');});
  render();updateZoom();
})();
