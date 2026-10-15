(() => {
  const icon = name => `<svg class="icon" aria-hidden="true"><use href="#i-${name}" /></svg>`;
  const escape = value => String(value).replace(/[&<>"']/g, char => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[char]);
  const samples = [
    { id:1, title:'沿着海岸，慢慢走', place:'山海之间', summary:'把时间留给海风，也留给沿途偶遇的小城。', image:'login-scenery.png', position:'center 57%', date:'2026-10-01', days:8, budget:2400, public:true, author:'示例旅人·南风', likes:24 },
    { id:2, title:'风花雪月，暂住几日', place:'湖边小城', summary:'先走进街巷，再坐下来等一场日落。', image:'lake-journey.png', position:'center', date:'', days:3, budget:null, public:false, author:'示例旅人·小舟', likes:9 },
    { id:3, title:'把周末留给山野', place:'云海之间', summary:'离开熟悉的街道，沿山路慢慢往前走。', image:'generate-scenery.png', position:'center', date:'2026-11-06', days:3, budget:1800, public:false, author:'示例旅人·山川', likes:15 },
    { id:4, title:'日落之前的海边散步', place:'海边灯塔', summary:'不急着赶路，给傍晚多留一点时间。', image:'square-scenery.png', position:'32% center', date:'2026-09-28', days:2, budget:0, public:true, author:'示例旅人·阿澄', likes:0 },
    { id:5, title:'雪山下的一段空白', place:'山的另一边', summary:'把想走的路写下来，慢慢填满这一段旅程。', image:'library-scenery.png', position:'66% center', date:'2026-12-12', days:4, budget:3200, public:false, author:'示例旅人·远行', likes:7 },
    { id:6, title:'从一座小城出发', place:'沿湖而行', summary:'一条街、一段水岸，都是新的出发点。', image:'lake-journey.png', position:'20% center', date:'2026-10-20', days:3, budget:1200, public:true, author:'示例旅人·林间', likes:18 },
  ];
  const lists = { trips:samples.slice(0,5), favorites:[samples[4],samples[1],samples[0],samples[3],samples[5],samples[2]], likes:[samples[2],samples[3],samples[5],samples[0],samples[1],samples[4]] };
  const meta = {
    trips:{ title:'我的路书', english:'My Trips', description:'计划、心动与走过的路，都在这里。', search:'搜索我的路书', recent:'最近更新' },
    favorites:{ title:'我的收藏', english:'Collections', description:'把喜欢的风景、路线和故事，留到下一次出发。', search:'搜索我的收藏', recent:'最近收藏' },
    likes:{ title:'我的点赞', english:'Likes', description:'每一个心动的旅程，都是下次出发的灵感。', search:'搜索我的点赞', recent:'最近点赞' },
  };
  const states = Object.fromEntries(Object.keys(meta).map(key => [key,{ query:'', sort:'recent', layout:'grid' }]));
  let view = 'trips';
  const titleRoot = document.querySelector('.library-title');
  const tabs = [...document.querySelectorAll('[data-tab]')];
  const input = document.querySelector('#library-search');
  const sort = document.querySelector('#library-sort');
  const panel = document.querySelector('#library-panel');
  const content = document.querySelector('#library-content');
  const empty = document.querySelector('#library-empty');
  const clear = document.querySelector('#clear-search');
  const invite = document.querySelector('#library-invite');
  const joinToggle = document.querySelector('#join-toggle');
  const joinResult = document.querySelector('#join-result');

  function revealTitle() {
    titleRoot.classList.remove('is-entering');
    void titleRoot.offsetWidth;
    titleRoot.classList.add('is-entering');
  }
  function media(item) {
    return `<div class="card-media" data-place="${escape(item.place)}"><img class="card-cover" src="assets/${item.image}" alt="${escape(item.place)}的概念配图，非真实路书封面" style="object-position:${item.position}" width="1672" height="941" /></div><span class="sample-badge">布局示例</span>`;
  }
  function tripCard(item, featured = false) {
    const budget = item.budget == null ? '预算待定' : `预估 ¥${item.budget.toLocaleString('zh-CN')}`;
    return `<a class="trip-card ${featured ? 'featured' : 'small-trip'}" data-card="${item.id}" href="/my-trips" aria-label="${escape(item.title)}，布局示例，进入正式旅行库">${media(item)}<div class="trip-copy"><span class="card-place">${icon('pin')}${escape(item.place)}</span><h3>${escape(item.title)}</h3><p class="trip-summary">${escape(item.summary)}</p><div class="trip-facts"><span>${icon('calendar')}${item.date || '日期待定'}</span><span>${icon('clock')}${item.days}天</span><span>${item.public ? '已发布' : '私密路书'}</span><span>${budget}</span></div></div>${featured ? `<span class="card-continue">${icon('arrow')}</span>` : ''}</a>`;
  }
  function storyCard(item) {
    return `<a class="story-card" data-card="${item.id}" href="/my-trips" aria-label="${escape(item.title)}，布局示例，进入正式旅行库">${media(item)}<span class="saved-mark">${icon(view === 'likes' ? 'heart' : 'bookmark')}</span><div class="saved-copy"><span class="card-place">${icon('pin')}${escape(item.place)} · ${item.days}天</span><h2>${escape(item.title)}</h2><div class="saved-facts"><span class="sample-author"><span class="author-letter" aria-hidden="true">${escape(item.author.slice(-2,-1))}</span>${escape(item.author)}</span><span class="sample-likes">${icon('heart')}示例点赞 ${item.likes}</span></div></div></a>`;
  }
  function renderCards() {
    const state = states[view];
    const query = state.query.trim().toLocaleLowerCase();
    const records = lists[view].filter(item => `${item.title} ${item.place} ${item.author}`.toLocaleLowerCase().includes(query));
    if (state.sort === 'title') records.sort((a,b) => a.title.localeCompare(b.title,'zh-CN'));
    if (state.sort === 'date') records.sort((a,b) => (b.date || '').localeCompare(a.date || ''));
    if (state.sort === 'likes') records.sort((a,b) => b.likes-a.likes || a.id-b.id);
    panel.classList.toggle('list-view',state.layout === 'list');
    for (const button of document.querySelectorAll('[data-layout]')) button.setAttribute('aria-pressed',String(button.dataset.layout === state.layout));
    document.querySelector('#library-results').textContent = query ? `“${state.query.trim()}” · 找到${records.length}份布局示例` : `当前展示${records.length}份布局示例。`;
    clear.hidden = !input.value;
    empty.hidden = records.length !== 0;
    content.hidden = records.length === 0;
    if (!records.length) { content.replaceChildren(); return; }
    if (view !== 'trips') { content.innerHTML = `<div class="saved-grid">${records.map(storyCard).join('')}</div>`; return; }
    if (state.layout === 'list') { content.innerHTML = `<div class="trip-shelf">${records.map(item => tripCard(item)).join('')}</div>`; return; }
    content.innerHTML = `<div class="trip-panels"><section class="recent-section" aria-labelledby="recent-heading"><div class="section-heading"><h2 id="recent-heading">${query ? '匹配的旅程' : '最近的旅程'}</h2>${icon('arrow')}</div>${tripCard(records[0],true)}</section>${records.length > 1 ? `<section class="shelf-section" aria-labelledby="shelf-heading"><div class="section-heading"><h2 id="shelf-heading">其他路书</h2>${icon('arrow')}</div><div class="trip-shelf">${records.slice(1).map(item => tripCard(item)).join('')}</div></section>` : ''}</div>`;
  }
  function selectView(next, push = false) {
    view = Object.hasOwn(meta,next) ? next : 'trips';
    const current = meta[view], state = states[view];
    if (push) { const url = new URL(location.href); url.searchParams.set('view',view); history.pushState({view},'',url); }
    document.title = `${current.title} · SmartTrip 旅行库设计预览`;
    const heading = titleRoot.querySelector('h1');
    heading.setAttribute('aria-label',current.title);
    heading.replaceChildren(document.createTextNode(current.title));
    const english = document.createElement('em'); english.textContent = current.english; english.lang='en'; english.setAttribute('aria-hidden','true'); heading.append(english);
    document.querySelector('#library-description').textContent = current.description;
    for (const tab of tabs) { const active=tab.dataset.tab === view; tab.setAttribute('aria-selected',String(active)); tab.tabIndex=active ? 0 : -1; }
    panel.setAttribute('aria-labelledby',`tab-${view}`);
    input.value=state.query; input.placeholder=current.search+'…'; input.setAttribute('aria-label',current.search);
    sort.innerHTML=`<option value="recent">${current.recent}</option><option value="title">按标题排序</option><option value="${view === 'trips' ? 'date' : 'likes'}">${view === 'trips' ? '按出发日期' : '按点赞数'}</option>`;
    sort.value=state.sort;
    renderCards(); revealTitle();
  }
  for (const tab of tabs) tab.addEventListener('click',event => { event.preventDefault(); if (tab.dataset.tab !== view) selectView(tab.dataset.tab,true); });
  document.querySelector('.library-tabs').addEventListener('keydown',event => {
    if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
    event.preventDefault();
    const index=tabs.findIndex(tab => tab.dataset.tab===view);
    const next=event.key==='Home' ? 0 : event.key==='End' ? tabs.length-1 : (index+(event.key==='ArrowRight' ? 1 : -1)+tabs.length)%tabs.length;
    selectView(tabs[next].dataset.tab,true); tabs[next].focus();
  });
  document.querySelector('#library-search-form').addEventListener('submit',event => { event.preventDefault(); states[view].query=input.value; renderCards(); });
  input.addEventListener('input',()=>{ clear.hidden=!input.value; });
  function resetSearch() { input.value=''; states[view].query=''; renderCards(); input.focus(); }
  clear.addEventListener('click',resetSearch);
  document.querySelector('#reset-search').addEventListener('click',resetSearch);
  sort.addEventListener('change',()=>{states[view].sort=sort.value;renderCards();});
  for (const button of document.querySelectorAll('[data-layout]')) button.addEventListener('click',()=>{states[view].layout=button.dataset.layout;renderCards();});
  document.querySelector('#cover-toggle').addEventListener('click',event=>{const active=document.body.classList.toggle('no-covers');event.currentTarget.setAttribute('aria-pressed',String(active));event.currentTarget.textContent=active?'恢复概念封面':'查看无封面效果';});
  function closeInvite() { invite.hidden=true; joinToggle.setAttribute('aria-expanded','false'); joinToggle.focus(); }
  joinToggle.addEventListener('click',()=>{if(!invite.hidden){closeInvite();return;}invite.hidden=false;joinToggle.setAttribute('aria-expanded','true');document.querySelector('#invite-code').focus();});
  document.querySelector('#close-invite').addEventListener('click',closeInvite);
  invite.addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();closeInvite();}});
  document.querySelector('#invite-form').addEventListener('submit',event=>{event.preventDefault();joinResult.textContent='这里只演示邀请入口，未加入真实路书。请在正式旅行库使用邀请口令。';joinResult.hidden=false;});
  const scenery=document.querySelector('#library-landscape');
  function sceneryFallback(){document.body.classList.add('image-failed');scenery.hidden=true;}
  scenery.addEventListener('error',sceneryFallback);
  if(scenery.complete&&!scenery.naturalWidth)sceneryFallback();
  document.addEventListener('error',event=>{if(event.target instanceof HTMLImageElement&&event.target.classList.contains('card-cover')){event.target.closest('.card-media').classList.add('image-failed');event.target.hidden=true;}},true);
  window.addEventListener('popstate',()=>selectView(new URLSearchParams(location.search).get('view')));
  window.addEventListener('pageshow',event=>{if(event.persisted)revealTitle();});
  selectView(new URLSearchParams(location.search).get('view'));
})();
