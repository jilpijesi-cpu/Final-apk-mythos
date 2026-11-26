(() => {
  'use strict';

  const STORAGE_KEY = 'claude-local-reconstruction-state-v1';
  const DEFAULT_MODEL = 'Sonnet 4.6';
  const API_BASE = localStorage.getItem('claude-gateway-url') || 'http://127.0.0.1:8787';
  let gatewayToken = sessionStorage.getItem('claude-gateway-token') || '';
  let activeStream = null;
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const uid = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2,8)}`;
  const timeLabel = date => {
    const d = new Date(date || Date.now());
    const now = new Date();
    if (d.toDateString() === now.toDateString()) return 'Today';
    const y = new Date(now); y.setDate(now.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return 'Yesterday';
    return d.toLocaleDateString(undefined, {month:'short', day:'numeric'});
  };

  const paths = {
    plus:'<path d="M12 5v14M5 12h14"/>', search:'<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    message:'<path d="M20 11.5a7.5 7.5 0 0 1-8 7.5 8.7 8.7 0 0 1-4-.9L4 20l1.5-3.4A7.1 7.1 0 0 1 4 11.5 7.5 7.5 0 0 1 12 4a7.5 7.5 0 0 1 8 7.5Z"/>',
    folder:'<path d="M3 7.5A1.5 1.5 0 0 1 4.5 6H10l2 2h7.5A1.5 1.5 0 0 1 21 9.5v8a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z"/>',
    grid:'<rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><rect x="14" y="14" width="6" height="6" rx="1"/>',
    terminal:'<path d="m5 7 5 5-5 5M12 17h7"/>', monitor:'<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>', settings:'<circle cx="12" cy="12" r="3"/><path d="m19.4 15 .1.1 1.2 1-1.5 2.6-1.5-.5a7.7 7.7 0 0 1-1.7 1l-.3 1.6h-3l-.3-1.6a7.7 7.7 0 0 1-1.7-1l-1.5.5-1.5-2.6 1.2-1a7.4 7.4 0 0 1 0-2l-1.2-1 1.5-2.6 1.5.5a7.7 7.7 0 0 1 1.7-1L12 7.3h3l.3 1.6a7.7 7.7 0 0 1 1.7 1l1.5-.5 1.5 2.6-1.2 1a7.4 7.4 0 0 1 0 2Z"/>',
    edit:'<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/>', menu:'<path d="M4 6h16M4 12h16M4 18h16"/>',
    sun:'<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>', moon:'<path d="M20.5 14.3A8.5 8.5 0 0 1 9.7 3.5 8.5 8.5 0 1 0 20.5 14.3Z"/>',
    paperclip:'<path d="m8 12.5 7.4-7.4a3 3 0 0 1 4.2 4.2l-9.2 9.2a5 5 0 0 1-7.1-7.1l9.2-9.2"/>', send:'<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>', mic:'<rect x="9" y="2" width="6" height="13" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-4 0h8"/>', globe:'<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
    sparkle:'<path d="m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"/><path d="m19 16 .8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8L19 16Z"/>',
    book:'<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v17H6.5A2.5 2.5 0 0 0 4 22Z"/><path d="M4 5.5v14A2.5 2.5 0 0 1 6.5 17H20"/>', calendar:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>', clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>', check:'<path d="m5 12 4 4L19 6"/>', copy:'<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>', trash:'<path d="M3 6h18M8 6V4h8v2m3 0-1 14H6L5 6m4 4v6m6-6v6"/>',
    arrow:'<path d="M5 12h14m-6-6 6 6-6 6"/>', chevron:'<path d="m6 9 6 6 6-6"/>', more:'<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>', close:'<path d="m6 6 12 12M18 6 6 18"/>',
    thumbs:'<path d="M7 10v11H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2Zm0 0 5-8c2 0 3 2 2 4l-1 4h6a2 2 0 0 1 2 2l-1 7a2 2 0 0 1-2 2H7"/>',
    file:'<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9Z"/><path d="M13 2v7h7M8 14h8M8 18h8"/>',
    code:'<path d="m8 8-4 4 4 4m8-8 4 4-4 4m-2-10-4 12"/>', people:'<path d="M16 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M10 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm10 10v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>',
    help:'<circle cx="12" cy="12" r="9"/><path d="M9.6 9a2.5 2.5 0 1 1 4.3 1.7c-1.1 1-1.9 1.3-1.9 3M12 17h.01"/>', lock:'<rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
    link:'<path d="M10 13a5 5 0 0 0 7.1 0l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1M14 11a5 5 0 0 0-7.1 0l-2 2a5 5 0 0 0 7.1 7.1l1.1-1.1"/>',
    chart:'<path d="M4 19V5m0 14h17M8 15l3-4 3 2 5-7"/>', zap:'<path d="m13 2-3 9h7l-6 11 1-9H5Z"/>',
  };
  function icon(name, cls='') { return `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.sparkle}</svg>`; }
  const mark = `<svg viewBox="0 0 40 40" aria-hidden="true"><path d="M20 2.5 23.3 14.8 35 9.5 27.4 20l10.1 7.2-13.4-.4L20 39l-4.1-12.2-13.4.4L12.6 20 5 9.5l11.7 5.3L20 2.5Z" fill="currentColor"/></svg>`;
  $$('[data-icon]').forEach(el => { el.innerHTML = icon(el.dataset.icon); });

  const defaultState = () => ({
    theme: 'light', model: DEFAULT_MODEL, provider:'anthropic', page: 'chat', activeChatId: null, chats: [], projects: [
      {id:'starter-project', name:'Getting started', description:'A place to organize related chats and reference material.', instructions:'Use this project to keep related work together.'}
    ], artifacts: [
      {id:'sample-brief', title:'Product brief', type:'Document', desc:'A short product strategy outline.', style:'notes'},
      {id:'sample-dashboard', title:'Monthly overview', type:'Chart', desc:'A visual snapshot of performance.', style:'chart'},
      {id:'sample-code', title:'Starter component', type:'Code', desc:'A small interactive UI example.', style:'code'}
    ], files: [], settings: {extendedThinking:false, webSearch:false, notifications:true, haptics:true, memory:true, privacyMode:false}, settingsSection:'account'
  });
  let state;
  try { state = {...defaultState(), ...JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')}; }
  catch { state = defaultState(); }
  state.settings = {...defaultState().settings, ...(state.settings || {})};
  state.chats = Array.isArray(state.chats) ? state.chats : [];
  state.projects = Array.isArray(state.projects) ? state.projects : defaultState().projects;
  state.artifacts = Array.isArray(state.artifacts) ? state.artifacts : defaultState().artifacts;

  function persist() { try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); } catch (e) { console.warn('Local save unavailable', e); } }
  function setTheme(theme) { state.theme = theme; document.documentElement.dataset.theme = theme; const meta = $('meta[name="theme-color"]'); if (meta) meta.content = theme === 'dark' ? '#22211f' : '#f7f5f0'; persist(); }
  setTheme(state.theme || 'light');

  const view = $('#view');
  const modalRoot = $('#modalRoot');
  const filePicker = $('#filePicker');
  const pageTitles = {chat:'Claude', search:'Search', projects:'Projects', artifacts:'Artifacts', code:'Code', cowork:'Cowork', settings:'Settings'};
  function closeSidebar() { $('#sidebar').classList.remove('open'); $('#sidebarScrim').classList.remove('show'); }
  function openSidebar() { $('#sidebar').classList.add('open'); $('#sidebarScrim').classList.add('show'); }

  function render() {
    document.body.dataset.page = state.page;
    $('#pageTitle').textContent = pageTitles[state.page] || 'Claude';
    $('#pageContext').textContent = state.page === 'chat' && state.activeChatId ? (getActiveChat()?.title || '') : '';
    $('#modelLabel').textContent = state.model;
    $$('.nav-item[data-page]').forEach(btn => btn.classList.toggle('active', btn.dataset.page === state.page));
    renderRecents();
    switch (state.page) {
      case 'chat': renderChat(); break;
      case 'search': renderSearch(); break;
      case 'projects': renderProjects(); break;
      case 'artifacts': renderArtifacts(); break;
      case 'code': renderCode(); break;
      case 'cowork': renderCowork(); break;
      case 'settings': renderSettings(); break;
      default: state.page = 'chat'; renderChat();
    }
  }
  function getActiveChat() { return state.chats.find(chat => chat.id === state.activeChatId); }
  function renderRecents() {
    const list = $('#recentList');
    const chats = [...state.chats].sort((a,b) => (b.updatedAt || 0) - (a.updatedAt || 0)).slice(0, 9);
    if (!chats.length) { list.innerHTML = '<div class="recent-empty">Your recent chats will appear here.</div>'; return; }
    list.innerHTML = chats.map(chat => `<button class="recent-item ${chat.id === state.activeChatId ? 'selected':''}" data-chat="${esc(chat.id)}" title="${esc(chat.title)}">${icon('message')}<span class="recent-title">${esc(chat.title)}</span></button>`).join('');
    $$('.recent-item', list).forEach(btn => btn.addEventListener('click', () => openChat(btn.dataset.chat)));
  }

  function welcomeMarkup() {
    const hour = new Date().getHours();
    const greeting = hour < 12 ? 'Good morning.' : hour < 18 ? 'Good afternoon.' : 'Good evening.';
    return `<div class="welcome-block"><div class="welcome-mark">${mark}</div><h1 class="welcome-title">${greeting}<br>What can I help you with?</h1><p class="welcome-subtitle">A thought partner for the things that matter.</p></div>
      <div class="suggestion-grid">
        <button class="suggestion-card" data-prompt="Help me write a clear, thoughtful email about a project update."><strong>✎ &nbsp; Write something</strong><span>Draft, revise, or find the right words</span></button>
        <button class="suggestion-card" data-prompt="Help me understand a complex topic step by step."><strong>◉ &nbsp; Learn something</strong><span>Explore an idea or explain a concept</span></button>
        <button class="suggestion-card" data-prompt="Help me plan a project, including milestones and next steps."><strong>▦ &nbsp; Make a plan</strong><span>Turn a goal into a practical plan</span></button>
        <button class="suggestion-card" data-prompt="Help me brainstorm several creative approaches to a problem."><strong>✳ &nbsp; Brainstorm ideas</strong><span>Think through possibilities together</span></button>
      </div>`;
  }

  function renderChat() {
    const chat = getActiveChat();
    const messages = chat?.messages || [];
    view.innerHTML = `<div class="chat-view"><div class="chat-main">${messages.length ? `<div class="conversation" id="conversation">${messages.map((msg,i) => messageMarkup(msg,i)).join('')}</div>` : welcomeMarkup()}</div>
      ${composerMarkup()}<div class="disclaimer">Claude can make mistakes. Check important information. <span class="demo-disclaimer">· Bring your own provider API key in Settings → AI Providers.</span></div></div>`;
    bindComposer();
    $$('[data-prompt]').forEach(btn => btn.addEventListener('click', () => { const ta = $('#composerInput'); ta.value = btn.dataset.prompt; ta.focus(); autosize(ta); updateSendState(); }));
    bindMessageActions();
    const conv = $('#conversation'); if (conv) conv.scrollTop = conv.scrollHeight;
  }
  function messageMarkup(msg, index) {
    if (msg.role === 'user') return `<article class="message user"><div class="message-body">${esc(msg.text)}</div></article>`;
    return `<article class="message assistant"><div class="assistant-mark">${mark}</div><div><div class="message-body">${renderSimpleMarkdown(msg.text)}</div><div class="message-actions"><button data-copy="${index}" title="Copy response">${icon('copy')} Copy</button><button data-feedback="up" title="Helpful">${icon('thumbs')} </button><button data-feedback="down" title="Not helpful">↘</button></div></div></article>`;
  }
  function renderSimpleMarkdown(text) {
    let safe = esc(text);
    safe = safe.replace(/```([\s\S]*?)```/g, (_,code) => `<pre><code>${code.trim()}</code></pre>`);
    safe = safe.replace(/^### (.*)$/gm, '<h3>$1</h3>');
    safe = safe.replace(/^## (.*)$/gm, '<h3>$1</h3>');
    safe = safe.replace(/^[-*] (.*)$/gm, '<li>$1</li>');
    safe = safe.replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>');
    safe = safe.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    safe = safe.replace(/`([^`]+)`/g, '<code>$1</code>');
    return safe.split(/\n\n+/).map(block => block.startsWith('<pre>') || block.startsWith('<h3>') || block.startsWith('<ul>') ? block : `<p>${block.replace(/\n/g,'<br>')}</p>`).join('');
  }
  function composerMarkup() {
    const files = (state.files || []).map((f,i) => `<div class="attachment-chip">${icon('file')}<span>${esc(f.name)}</span><button data-remove-file="${i}" aria-label="Remove file">×</button></div>`).join('');
    return `<div class="composer-wrap"><div class="composer"><div id="attachmentStrip" class="attachment-strip">${files}</div><textarea id="composerInput" rows="1" placeholder="Message Claude..." aria-label="Message Claude"></textarea><div class="composer-toolbar"><div class="toolbar-left"><button class="composer-icon" id="attachBtn" title="Add files">${icon('paperclip')}</button><button class="mode-chip ${state.settings.webSearch?'active':''}" id="webSearchBtn">${icon('globe')} Search</button><button class="mode-chip ${state.settings.extendedThinking?'active':''}" id="thinkingBtn">${icon('sparkle')} Think</button></div><div class="toolbar-right"><button class="composer-icon" id="voiceBtn" title="Voice mode">${icon('mic')}</button><button class="send-btn" id="sendBtn" disabled aria-label="Send message">${icon('send')}</button></div></div></div></div>`;
  }
  function autosize(el) { el.style.height = 'auto'; el.style.height = `${Math.min(el.scrollHeight,180)}px`; }
  function updateSendState() { const btn = $('#sendBtn'); const ta = $('#composerInput'); if (btn && ta) btn.disabled = !ta.value.trim() && !(state.files || []).length; }
  function bindComposer() {
    const ta = $('#composerInput'); if (!ta) return;
    ta.addEventListener('input', () => { autosize(ta); updateSendState(); });
    ta.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } });
    $('#sendBtn').addEventListener('click', sendMessage);
    $('#attachBtn').addEventListener('click', () => filePicker.click());
    $('#webSearchBtn').addEventListener('click', () => { state.settings.webSearch = !state.settings.webSearch; persist(); renderChat(); $('#composerInput')?.focus(); });
    $('#thinkingBtn').addEventListener('click', () => { state.settings.extendedThinking = !state.settings.extendedThinking; persist(); renderChat(); $('#composerInput')?.focus(); });
    $('#voiceBtn').addEventListener('click', startVoiceInput);
    $$('[data-remove-file]').forEach(btn => btn.addEventListener('click', () => { state.files.splice(Number(btn.dataset.removeFile),1); renderChat(); }));
    updateSendState();
  }
  filePicker.addEventListener('change', () => {
    const added = [...filePicker.files].map(file => ({name:file.name, size:file.size, type:file.type}));
    state.files = [...(state.files || []), ...added].slice(0,8); filePicker.value = ''; render();
  });
  function startVoiceInput() {
    const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!Recognition) { toast('Voice input is not available in this browser.'); return; }
    const recognition = new Recognition(); recognition.lang = 'en-US'; recognition.interimResults = false;
    recognition.onresult = event => { const ta = $('#composerInput'); if (ta) { ta.value = `${ta.value}${ta.value ? ' ' : ''}${event.results[0][0].transcript}`; autosize(ta); updateSendState(); } };
    recognition.onerror = () => toast('Voice capture did not start.'); recognition.start(); toast('Listening… speak now.');
  }
  async function sendMessage() {
    if(activeStream){activeStream.abort();activeStream=null;toast('Generation stopped');return;}
    const ta=$('#composerInput');if(!ta)return;const text=ta.value.trim(),attached=state.files||[];if(!text&&!attached.length)return;
    let chat=getActiveChat();if(!chat){chat={id:uid(),title:makeTitle(text||attached[0]?.name||'New chat'),messages:[],createdAt:Date.now(),updatedAt:Date.now()};state.chats.unshift(chat);state.activeChatId=chat.id;}
    const composed=text+(attached.length?`\n\n[Attached: ${attached.map(f=>f.name).join(', ')}]`:'');chat.messages.push({role:'user',text:composed,createdAt:Date.now()});chat.updatedAt=Date.now();if(chat.messages.length===1&&text)chat.title=makeTitle(text);state.files=[];persist();render();
    const assistant={role:'assistant',text:'',createdAt:Date.now(),streaming:true};chat.messages.push(assistant);persist();render();
    const conv=$('#conversation');const controller=new AbortController();activeStream=controller;
    const send=$('#sendBtn');if(send){send.innerHTML='Ⅱ';send.disabled=false;send.setAttribute('aria-label','Stop generation');}
    try{
      const history=chat.messages.slice(0,-1).map(m=>({role:m.role,content:m.text}));
      const res=await fetch(API_BASE+'/v1/chat/completions',{method:'POST',headers:{'content-type':'application/json','authorization':'Bearer '+gatewayToken},signal:controller.signal,body:JSON.stringify({provider:state.provider||'anthropic',model:state.model,messages:history})});
      if(!res.ok)throw new Error((await res.json().catch(()=>({}))).error||`Gateway error ${res.status}`);
      const reader=res.body.getReader(),decoder=new TextDecoder();let buffer='';
      while(true){const {value,done}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true});let idx;while((idx=buffer.indexOf('\n\n'))>=0){const frame=buffer.slice(0,idx);buffer=buffer.slice(idx+2);const ev=(frame.match(/^event: (.+)$/m)||[])[1],data=(frame.match(/^data: (.+)$/m)||[])[1];if(!data)continue;const obj=JSON.parse(data);if(ev==='text'){assistant.text+=obj.text;const node=$('#conversation .message.assistant:last-child .message-body');if(node)node.innerHTML=renderSimpleMarkdown(assistant.text);const c=$('#conversation');if(c)c.scrollTop=c.scrollHeight;}if(ev==='error')throw new Error(obj.error||'Streaming error');}}
      assistant.streaming=false;if(!assistant.text)assistant.text='(Provider returned an empty response.)';
    }catch(e){if(e.name==='AbortError'){assistant.text+='\n\n[Generation stopped]';}else{assistant.text=assistant.text?assistant.text+`\n\n[Streaming error: ${e.message}]`:`Gateway error: ${e.message}. Configure a provider in Settings → AI Providers.`;}}
    finally{activeStream=null;chat.updatedAt=Date.now();persist();if(state.activeChatId===chat.id)render();}
  }
  function makeTitle(text) { const normalized = String(text).replace(/\s+/g,' ').trim(); return normalized.length > 42 ? normalized.slice(0,39) + '…' : normalized || 'New chat'; }
  function localResponse(prompt, files) {
    const t = String(prompt || '').toLowerCase();
    const fileText = files?.length ? `\n\nI can see the attachment names (${files.map(f=>f.name).join(', ')}), but this offline build does not parse file contents.` : '';
    const localNote = '\n\n---\n*This is a local demo response, not a reply generated by Claude’s hosted models.*';
    if (/^(hi|hello|hey|halo|hai|pagi|selamat pagi|selamat siang|selamat malam)\b/.test(t)) return `Hello! What would you like to work on today?${localNote}`;
    if (/\b(translate|terjemah|translate to|artikan)\b/.test(t)) return `I can help with translation structure, tone, and terminology. In this local build, I cannot call a hosted language model, so the actual translation needs to be supplied by an AI backend.\n\nFor a good translation workflow, specify the source text, target language, audience, and whether you prefer a literal or natural translation.${fileText}${localNote}`;
    if (/\b(code|javascript|python|function|bug|error|program|html|css|sql)\b/.test(t)) return `I can help reason through implementation details. A useful first pass is:\n\n1. **Define the expected behaviour** and the inputs/outputs.\n2. **Reproduce the issue** with the smallest example.\n3. **Check edge cases** and error handling.\n4. **Add a focused test** before changing more code.\n\nShare the relevant snippet and the exact error or expected result for a more targeted review.${fileText}${localNote}`;
    if (/\b(plan|project|roadmap|jadwal|rencana|milestone)\b/.test(t)) return `A practical way to structure this is:\n\n### 1. Define the outcome\nWrite one measurable result and a clear definition of done.\n\n### 2. Break the work into phases\n- Discovery and requirements\n- First working version\n- Review and iteration\n- Release and follow-up\n\n### 3. Assign ownership\nGive each milestone one directly responsible owner, a due date, and a dependency check.\n\n### 4. Review progress\nUse a short weekly review to surface blockers and revise estimates.${localNote}`;
    if (/\b(write|email|draft|message|memo|surat)\b/.test(t)) return `I can help shape the draft. A strong starting structure is:\n\n- **Purpose:** state the reason for writing in the first sentence.\n- **Context:** include only the background the reader needs.\n- **Request or next step:** make the action, owner, and timing explicit.\n- **Close:** keep the tone warm and direct.\n\nShare the audience, desired tone, and key facts to turn this outline into a complete draft.${localNote}`;
    return `I’ve saved your message in this local conversation.\n\nThis reconstruction includes the interface and local interaction flows, but it is not connected to Anthropic’s private conversation services. To get an actual model-generated answer, the deployment needs an authorized API/backend connection.\n\nFor this prompt, the next useful step is to clarify the outcome you need, any constraints, and what a successful answer should include.${fileText}${localNote}`;
  }
  function bindMessageActions() {
    $$('[data-copy]').forEach(btn => btn.addEventListener('click', () => {
      const chat = getActiveChat(); const msg = chat?.messages?.[Number(btn.dataset.copy)]; if (msg) { navigator.clipboard?.writeText(msg.text).then(()=>toast('Copied to clipboard')).catch(()=>toast('Clipboard access unavailable')); }
    }));
    $$('[data-feedback]').forEach(btn => btn.addEventListener('click', () => toast('Feedback saved for this local demo.')));
  }
  function openChat(id) { state.activeChatId = id; state.page = 'chat'; persist(); closeSidebar(); render(); }
  function newChat() { state.page = 'chat'; state.activeChatId = null; state.files = []; persist(); closeSidebar(); render(); setTimeout(()=>$('#composerInput')?.focus(),40); }

  function renderSearch() {
    view.innerHTML = `<div class="page-wrap"><div class="page-heading"><div><h1>Search</h1><p>Find a conversation by title or message content.</p></div></div><div class="search-box">${icon('search')}<input id="chatSearchInput" type="search" placeholder="Search your chats..." autocomplete="off"></div><div class="filter-row"><button class="filter-chip active" data-search-filter="all">All</button><button class="filter-chip" data-search-filter="chats">Chats</button><button class="filter-chip" data-search-filter="projects">Projects</button></div><div id="searchResults" class="search-results"></div></div>`;
    const input = $('#chatSearchInput'); const results = $('#searchResults'); let filter = 'all';
    function update() {
      const q = input.value.trim().toLowerCase();
      const hits = state.chats.filter(chat => filter !== 'projects' && (!q || chat.title.toLowerCase().includes(q) || chat.messages.some(m=>m.text.toLowerCase().includes(q))));
      if (!q) { results.innerHTML = `<div class="empty-state"><div class="empty-symbol">⌕</div><h3>Search your conversations</h3><p>Start typing to find messages and chats saved in this browser.</p></div>`; return; }
      results.innerHTML = hits.length ? hits.map(chat => `<div class="list-row" data-open-chat="${esc(chat.id)}"><div class="row-icon">${icon('message')}</div><div class="row-copy"><strong>${esc(chat.title)}</strong><small>${esc(chat.messages.at(-1)?.text || 'No messages')}</small></div><time>${timeLabel(chat.updatedAt)}</time></div>`).join('') : `<div class="empty-state"><div class="empty-symbol">⌕</div><h3>No results found</h3><p>Try another search term.</p></div>`;
      $$('[data-open-chat]', results).forEach(row=>row.addEventListener('click',()=>openChat(row.dataset.openChat)));
    }
    input.addEventListener('input', update);
    $$('[data-search-filter]').forEach(btn=>btn.addEventListener('click',()=>{filter=btn.dataset.searchFilter; $$('[data-search-filter]').forEach(x=>x.classList.toggle('active',x===btn)); update();}));
    update(); setTimeout(()=>input.focus(),60);
  }

  function renderProjects() {
    view.innerHTML = `<div class="page-wrap"><div class="page-heading"><div><h1>Projects</h1><p>Keep chats, files, and instructions together in one workspace.</p></div><button class="primary-btn" id="createProjectBtn">${icon('plus')} New project</button></div><div class="card-grid">${state.projects.map(project=>`<article class="surface-card interactive" data-project="${esc(project.id)}"><div class="card-icon">${icon('folder')}</div><h3>${esc(project.name)}</h3><p>${esc(project.description || 'A workspace for related conversations and files.')}</p><div class="card-meta"><span>Project</span><span>${(project.chats||[]).length} chats</span></div></article>`).join('')}</div>${state.projects.length ? '' : `<div class="empty-state"><div class="empty-symbol">▱</div><h3>No projects yet</h3><p>Create a project to organize conversations and reference files.</p></div>`}</div>`;
    $('#createProjectBtn').addEventListener('click', showCreateProject);
    $$('[data-project]').forEach(card=>card.addEventListener('click',()=>showProjectDetail(card.dataset.project)));
  }
  function showCreateProject() {
    showModal('Create a project', `<p>Give your workspace a name and a little context. You can edit these details later.</p><label class="field-label">Project name</label><input id="projectName" class="modal-input" maxlength="80" placeholder="e.g. Website redesign"><label class="field-label">Description</label><textarea id="projectDesc" class="modal-textarea" placeholder="What are you working on?"></textarea>`, `<button class="secondary-btn" data-modal-close>Cancel</button><button class="primary-btn" id="saveProject">Create project</button>`);
    $('#saveProject').addEventListener('click',()=>{ const name=$('#projectName').value.trim(); if(!name){toast('Enter a project name first.');return;} state.projects.unshift({id:uid(),name,description:$('#projectDesc').value.trim(),instructions:'',chats:[],createdAt:Date.now()}); persist(); closeModal(); render(); toast('Project created.'); });
  }
  function showProjectDetail(id) {
    const project = state.projects.find(p=>p.id===id); if(!project)return;
    view.innerHTML = `<div class="page-wrap"><div class="page-heading"><button class="quiet-btn" id="backProjects">← Projects</button><button class="quiet-btn" id="projectMore">${icon('more')} More</button></div><div class="project-hero"><div class="card-icon">${icon('folder')}</div><div><h1>${esc(project.name)}</h1><p>${esc(project.description || 'Your project workspace')}</p></div></div><div class="template-bar"><div><strong>Project instructions</strong><span>${project.instructions ? esc(project.instructions) : 'Add context so Claude can respond with this project in mind.'}</span></div><button class="secondary-btn" id="editInstructions">Edit</button></div><div class="section-label">Project chats</div>${(project.chats||[]).length ? (project.chats||[]).map(id=>state.chats.find(c=>c.id===id)).filter(Boolean).map(chat=>`<div class="list-row" data-open-chat="${esc(chat.id)}"><div class="row-icon">${icon('message')}</div><div class="row-copy"><strong>${esc(chat.title)}</strong><small>${esc(chat.messages.at(-1)?.text || '')}</small></div></div>`).join('') : `<div class="empty-state"><div class="empty-symbol">▱</div><h3>No chats in this project</h3><p>Start a new project chat and keep the conversation here.</p></div>`}<button class="primary-btn" id="projectNewChat">${icon('plus')} New chat in project</button><div class="section-label">Knowledge</div><div class="template-bar"><div><strong>Project knowledge</strong><span>Reference files and notes for this workspace.</span></div><button class="secondary-btn" id="addKnowledge">Add content</button></div></div>`;
    $('#backProjects').addEventListener('click',()=>renderProjects());
    $('#editInstructions').addEventListener('click',()=>{ showModal('Project instructions',`<p>Set instructions that apply to chats in this project.</p><textarea class="modal-textarea" id="instructionsInput">${esc(project.instructions||'')}</textarea>`,`<button class="secondary-btn" data-modal-close>Cancel</button><button class="primary-btn" id="saveInstructions">Save</button>`); $('#saveInstructions').addEventListener('click',()=>{project.instructions=$('#instructionsInput').value.trim();persist();closeModal();showProjectDetail(id);}); });
    $('#projectNewChat').addEventListener('click',()=>{ const chat={id:uid(),title:'New project chat',messages:[],createdAt:Date.now(),updatedAt:Date.now(),projectId:id};state.chats.unshift(chat);project.chats=[...(project.chats||[]),chat.id];state.activeChatId=chat.id;state.page='chat';persist();render(); });
    $('#addKnowledge').addEventListener('click',()=>{filePicker.click();toast('Choose files to attach them in a new chat, or add project knowledge in the full service.');});
    $('#projectMore').addEventListener('click',()=>{ showModal('Project options',`<p>Manage this local project workspace.</p>`,`<button class="secondary-btn" data-modal-close>Close</button><button class="danger-btn" id="deleteProject">Delete project</button>`); $('#deleteProject').addEventListener('click',()=>{state.projects=state.projects.filter(p=>p.id!==id);persist();closeModal();renderProjects();toast('Project deleted.');}); });
    $$('[data-open-chat]').forEach(row=>row.addEventListener('click',()=>openChat(row.dataset.openChat)));
  }

  function artifactPreview(style) {
    if (style==='chart') return `<div class="artifact-preview chart"><svg viewBox="0 0 300 122"><path d="M24 97H278M24 20V97" stroke="#9c9b8e" stroke-opacity=".5" fill="none"/><path d="M28 82 75 63 116 72 162 39 207 52 267 24" fill="none" stroke="#718b78" stroke-width="3" stroke-linecap="round"/><path d="M28 82 75 63 116 72 162 39 207 52 267 24V97H28Z" fill="#8fa695" fill-opacity=".18"/><circle cx="267" cy="24" r="4" fill="#718b78"/></svg></div>`;
    if (style==='code') return `<div class="artifact-preview code"><div style="padding:16px;color:#e8e1d4;font:10px/1.8 ui-monospace,monospace"><span style="color:#c68f70">const</span> <span style="color:#a9c3a2">hello</span> = () =&gt; {<br>&nbsp;&nbsp;<span style="color:#c9a5c9">return</span> "Hello, world";<br>};<br><span style="color:#8ca89c">// ready to build</span></div></div>`;
    return `<div class="artifact-preview notes"><div style="padding:18px 20px;color:#514a40"><div style="height:5px;width:34%;background:#b6a18b;border-radius:4px;margin-bottom:13px"></div><div style="height:4px;width:70%;background:#c8b9a7;border-radius:4px;margin-bottom:8px"></div><div style="height:4px;width:61%;background:#d0c2b2;border-radius:4px;margin-bottom:8px"></div><div style="height:4px;width:79%;background:#d0c2b2;border-radius:4px"></div></div></div>`;
  }
  function renderArtifacts() {
    view.innerHTML = `<div class="page-wrap"><div class="page-heading"><div><h1>Artifacts</h1><p>Explore interactive content and files created in conversations.</p></div><button class="secondary-btn" id="artifactCreate">${icon('plus')} New artifact</button></div><div class="search-box">${icon('search')}<input id="artifactSearch" placeholder="Search artifacts..." type="search"></div><div class="filter-row"><button class="filter-chip active" data-artifact-filter="all">All</button><button class="filter-chip" data-artifact-filter="Document">Documents</button><button class="filter-chip" data-artifact-filter="Chart">Charts</button><button class="filter-chip" data-artifact-filter="Code">Code</button></div><div class="card-grid" id="artifactGrid">${artifactCards(state.artifacts)}</div></div>`;
    $('#artifactCreate').addEventListener('click',()=>{ showModal('Create an artifact',`<p>Start a new local artifact. In the original service, artifacts are created by a conversation or a tool.</p><label class="field-label">Title</label><input id="artifactTitle" class="modal-input" placeholder="Artifact title"><label class="field-label">Type</label><select id="artifactType" class="modal-input"><option>Document</option><option>Chart</option><option>Code</option></select>`,`<button class="secondary-btn" data-modal-close>Cancel</button><button class="primary-btn" id="saveArtifact">Create</button>`); $('#saveArtifact').addEventListener('click',()=>{const title=$('#artifactTitle').value.trim()||'Untitled artifact';const type=$('#artifactType').value;state.artifacts.unshift({id:uid(),title,type,desc:'Created locally in this reconstruction.',style:type==='Chart'?'chart':type==='Code'?'code':'notes'});persist();closeModal();renderArtifacts();toast('Artifact created.');}); });
    const search=$('#artifactSearch'); let filter='all';
    function update(){const q=search.value.toLowerCase();const list=state.artifacts.filter(a=>(filter==='all'||a.type===filter)&&(!q||`${a.title} ${a.desc}`.toLowerCase().includes(q)));$('#artifactGrid').innerHTML=artifactCards(list); bindArtifactCards();}
    search.addEventListener('input',update); $$('[data-artifact-filter]').forEach(btn=>btn.addEventListener('click',()=>{filter=btn.dataset.artifactFilter;$$('[data-artifact-filter]').forEach(b=>b.classList.toggle('active',b===btn));update();})); bindArtifactCards();
  }
  function artifactCards(list) { return list.map(a=>`<article class="surface-card interactive" data-artifact="${esc(a.id)}">${artifactPreview(a.style)}<h3>${esc(a.title)}</h3><p>${esc(a.desc)}</p><span class="badge-text">${esc(a.type)}</span></article>`).join('') || `<div class="empty-state"><div class="empty-symbol">▧</div><h3>No artifacts found</h3><p>Try another search or create a new artifact.</p></div>`; }
  function bindArtifactCards() { $$('[data-artifact]').forEach(card=>card.addEventListener('click',()=>{const a=state.artifacts.find(x=>x.id===card.dataset.artifact); if(a) showModal(a.title,`${artifactPreview(a.style)}<p>${esc(a.desc)}</p><p>This is sample content stored only in this browser.</p>`,`<button class="secondary-btn" data-modal-close>Close</button><button class="danger-btn" id="deleteArtifact">Delete</button>`); $('#deleteArtifact')?.addEventListener('click',()=>{state.artifacts=state.artifacts.filter(x=>x.id!==card.dataset.artifact);persist();closeModal();renderArtifacts();});})); }

  function renderCode() {
    view.innerHTML = `<div class="page-wrap"><div class="page-heading"><div><h1>Code</h1><p>Work with repositories, development sessions, and cloud environments.</p></div><button class="primary-btn" id="newCodeSession">${icon('plus')} New session</button></div><div class="template-bar"><div><strong>Connect GitHub</strong><span>Connect a repository to start a Code session. This local demo does not authorize external accounts.</span></div><button class="secondary-btn" id="connectGithub">${icon('link')} Connect</button></div><div class="section-label">Overview</div><div class="card-grid"><article class="surface-card"><div class="card-icon">${icon('folder')}</div><h3>Projects</h3><p>Keep repositories and sessions organized by project.</p><div class="card-meta"><span>Repositories</span><strong>0</strong></div></article><article class="surface-card"><div class="card-icon">${icon('terminal')}</div><h3>Sessions</h3><p>Continue coding work in a remote environment.</p><div class="card-meta"><span>Active sessions</span><strong>0</strong></div></article><article class="surface-card"><div class="card-icon">${icon('calendar')}</div><h3>Routines</h3><p>Automate recurring development tasks.</p><div class="card-meta"><span>Scheduled</span><strong>0</strong></div></article></div><div class="section-label">Getting started</div><div class="code-terminal"><div class="dim">// Claude Code · local reconstruction</div><div><span class="ok">$</span> connect a GitHub repository</div><div class="dim"># An authenticated remote workspace is required to run code.</div><div><span class="ok">$</span> create a session</div><div class="dim"># Connect the original Claude Code service to execute remote sessions.</div></div><div class="code-stat"><strong>Cloud environments</strong><span>Not connected</span></div><div class="code-stat"><strong>Notifications</strong><span>Local demo</span></div></div>`;
    $('#newCodeSession').addEventListener('click',()=>{ showModal('Start a Code session',`<p>A remote development environment is required for a real session.</p><label class="field-label">Session name</label><input class="modal-input" id="codeSessionName" placeholder="e.g. Fix login flow">`,`<button class="secondary-btn" data-modal-close>Cancel</button><button class="primary-btn" id="createCodeSession">Continue</button>`); $('#createCodeSession').addEventListener('click',()=>{const name=$('#codeSessionName').value.trim()||'New Code session';closeModal();showModal('Code session unavailable',`<p>“${esc(name)}” is ready to be created, but this reconstruction has no authenticated Claude Code backend or connected repository.</p>`,`<button class="primary-btn" data-modal-close>Got it</button>`);}); });
    $('#connectGithub').addEventListener('click',()=>showModal('Connect GitHub',`<p>Repository access is handled through Anthropic's authenticated connector flow. This offline build cannot request or store GitHub credentials.</p>`,`<button class="primary-btn" data-modal-close>Close</button>`));
  }
  function renderCowork() {
    view.innerHTML = `<div class="page-wrap"><div class="page-heading"><div><h1>Cowork</h1><p>Delegate multi-step work and keep an eye on tasks as they run.</p></div><button class="primary-btn" id="coworkNew">${icon('plus')} New task</button></div><div class="template-bar"><div><strong>Work with Claude across your desktop</strong><span>Connect a remote device to let Claude work with files and applications.</span></div><button class="secondary-btn" id="coworkConnect">Connect device</button></div><div class="section-label">Your tasks</div><div id="coworkTasks"><div class="empty-state"><div class="empty-symbol">✳</div><h3>No tasks yet</h3><p>When you delegate work, tasks and their progress will appear here.</p></div></div><div class="section-label">Scheduled tasks</div><div class="surface-card"><div style="display:flex;align-items:center;gap:11px"><div class="card-icon" style="margin:0">${icon('calendar')}</div><div style="flex:1"><h3 style="margin-bottom:3px">Scheduled runs</h3><p>Manage recurring work and automated tasks.</p></div><button class="secondary-btn" id="scheduledManage">View</button></div></div></div>`;
    $('#coworkNew').addEventListener('click',()=>{ showModal('Create a Cowork task',`<p>Describe the outcome and the work you would like Claude to take care of.</p><label class="field-label">Task</label><textarea id="coworkTaskText" class="modal-textarea" placeholder="What should be done?"></textarea><label class="field-label">When</label><select id="coworkWhen" class="modal-input"><option>Run now</option><option>Tomorrow</option><option>Every week</option></select>`,`<button class="secondary-btn" data-modal-close>Cancel</button><button class="primary-btn" id="createCoworkTask">Create task</button>`); $('#createCoworkTask').addEventListener('click',()=>{const task=$('#coworkTaskText').value.trim();if(!task){toast('Describe the task first.');return;}const when=$('#coworkWhen').value;closeModal();showModal('Task saved locally',`<p>Your task description has been noted in this session, but no remote worker is connected to execute it.</p><div class="surface-card"><strong style="font-size:11px">${esc(task)}</strong><p style="margin-top:7px">${esc(when)} · Waiting for a connected service</p></div>`,`<button class="primary-btn" data-modal-close>Done</button>`);}); });
    $('#coworkConnect').addEventListener('click',()=>showModal('Connect a device',`<p>Cowork requires a signed-in Claude account and an authorized device or computer session. That service is not included in this offline reconstruction.</p>`,`<button class="primary-btn" data-modal-close>Close</button>`));
    $('#scheduledManage').addEventListener('click',()=>toast('No scheduled tasks in this local demo.'));
  }

  const settingSections = [
    ['account','Account'],['providers','AI Providers'],['capabilities','Capabilities'],['connectors','Connectors'],['notifications','Notifications'],['appearance','Appearance'],['privacy','Privacy'],['claude-code','Claude Code'],['help','Help']
  ];
  function renderSettings() {
    const section = settingSections.some(s=>s[0]===state.settingsSection) ? state.settingsSection : 'account';
    const sectionMarkup = settingsSectionMarkup(section);
    view.innerHTML = `<div class="page-wrap"><div class="page-heading"><div><h1>Settings</h1><p>Manage your account and application preferences.</p></div></div><div class="settings-layout"><nav class="settings-nav">${settingSections.map(([id,label])=>`<button data-settings-section="${id}" class="${id===section?'active':''}">${label}</button>`).join('')}</nav><div class="settings-content">${sectionMarkup}</div></div></div>`;
    $$('[data-settings-section]').forEach(btn=>btn.addEventListener('click',()=>{state.settingsSection=btn.dataset.settingsSection;persist();renderSettings();}));
    bindSettingsControls();
    if(section==='providers') bindProviderSettings();
  }
  function settingsSectionMarkup(section) {
    const rows = (items) => items.map(item=>`<div class="setting-row"><div class="setting-copy"><strong>${item.title}</strong><small>${item.desc}</small></div>${item.control}</div>`).join('');
    if (section==='account') return `<h2>Account</h2><p>Personal information and subscription details.</p>${rows([
      {title:'Signed in as',desc:'This reconstruction uses a local demo profile.',control:'<span class="badge-text">Local account</span>'},
      {title:'Plan',desc:'The original app provides plan and billing management.',control:'<button class="secondary-btn" data-account-action="plan">View plans</button>'},
      {title:'Email address',desc:'No account email is connected in this local build.',control:'<button class="secondary-btn" data-account-action="email">Not connected</button>'},
      {title:'Sign out',desc:'Clear the local demo profile and saved conversations.',control:'<button class="danger-btn" id="localReset">Reset local data</button>'}
    ])}`;
    if (section==='providers') return `<h2>AI Providers</h2><p>Connect your own API keys. Keys are sent only to your configured gateway and stored encrypted there.</p><div class="surface-card" style="margin-bottom:14px"><div class="setting-row"><div class="setting-copy"><strong>Gateway connection</strong><small id="gatewayStatus">Checking local Android gateway…</small></div><button class="secondary-btn" id="gatewayConfig">Configure</button></div></div><div id="providerList"><p>Loading providers…</p></div><button class="primary-btn" id="addProvider">＋ Add provider</button><p style="margin-top:12px;font-size:11px">Supported: Anthropic, OpenAI, Gemini, OpenRouter, DeepSeek, Groq, Mistral, xAI, and compatible endpoints. Never share API keys with anyone.</p>`;
    if (section==='capabilities') return `<h2>Capabilities</h2><p>Choose which features are available during conversations.</p>${rows([
      {title:'Web search',desc:'Allow the assistant to look up current information. Requires a connected service.',control:toggleButton('webSearch')},
      {title:'Extended thinking',desc:'Use more time for complex problems. Simulated in this local build.',control:toggleButton('extendedThinking')},
      {title:'Memory',desc:'Keep preferences and context between conversations in the original service.',control:toggleButton('memory')},
      {title:'File uploads',desc:'Attach local files to a conversation. File contents are not processed here.',control:'<button class="secondary-btn" id="testUpload">Choose files</button>'}
    ])}`;
    if (section==='connectors') return `<h2>Connectors</h2><p>Connect services so Claude can work with your tools and information.</p><div class="surface-card" style="margin-bottom:13px"><h3>Available connectors</h3><p>Connector authorization requires an authenticated Claude organization.</p></div>${['Google Calendar','Google Drive','GitHub','Slack','Notion'].map(name=>`<div class="setting-row"><div class="setting-copy"><strong>${name}</strong><small>Not connected</small></div><button class="secondary-btn" data-connector="${esc(name)}">Connect</button></div>`).join('')}`;
    if (section==='notifications') return `<h2>Notifications</h2><p>Choose which updates you receive from the app.</p>${rows([
      {title:'Push notifications',desc:'Get notified about replies and activity.',control:toggleButton('notifications')},
      {title:'Haptic feedback',desc:'Use vibration for supported interface interactions.',control:toggleButton('haptics')},
      {title:'Code notifications',desc:'Updates for remote Code sessions.',control:'<button class="secondary-btn" data-account-action="notifications">Manage</button>'}
    ])}`;
    if (section==='appearance') return `<h2>Appearance</h2><p>Adjust how Claude looks on this device.</p>${rows([
      {title:'Theme',desc:'Choose a light or dark interface.',control:`<select class="setting-select" id="themeSelect"><option value="light" ${state.theme==='light'?'selected':''}>Light</option><option value="dark" ${state.theme==='dark'?'selected':''}>Dark</option></select>`},
      {title:'Accent',desc:'Warm accent colour used for highlights.',control:'<span class="badge-text">Terracotta</span>'},
      {title:'Interface density',desc:'The reconstruction uses a compact mobile-first layout.',control:'<span class="badge-text">Default</span>'}
    ])}`;
    if (section==='privacy') return `<h2>Privacy</h2><p>Control local data and privacy-related preferences.</p>${rows([
      {title:'Privacy mode',desc:'Keep new conversations in this browser only. No remote service is connected.',control:toggleButton('privacyMode')},
      {title:'Clear chat history',desc:'Remove conversations saved in this browser.',control:'<button class="danger-btn" id="clearChats">Clear history</button>'},
      {title:'Data controls',desc:'Anthropic account privacy controls are not available in this local replica.',control:'<span class="badge-text">Unavailable offline</span>'}
    ])}`;
    if (section==='claude-code') return `<h2>Claude Code</h2><p>Configure remote development sessions and permissions.</p>${rows([
      {title:'Code sessions',desc:'Connect a repository and an authorized remote environment to enable sessions.',control:'<button class="secondary-btn" data-page-link="code">Open Code</button>'},
      {title:'Permission mode',desc:'Session permissions are managed by the remote Code environment.',control:'<span class="badge-text">Not connected</span>'},
      {title:'Notifications',desc:'Remote session notifications.',control:toggleButton('notifications')}
    ])}`;
    return `<h2>Help</h2><p>Product support and legal information.</p>${rows([
      {title:'Help center',desc:'The original app links to support resources.',control:'<button class="secondary-btn" data-account-action="help">Open help</button>'},
      {title:'About this reconstruction',desc:'Interface and local interactions reconstructed from the supplied Android APK.',control:'<span class="badge-text">Build 1.0.0</span>'},
      {title:'Backend status',desc:'Not connected to Anthropic services.',control:'<span class="badge-text">Offline demo</span>'}
    ])}`;
  }
  function toggleButton(key) { return `<button class="toggle ${state.settings[key]?'on':''}" data-toggle-setting="${key}" aria-label="Toggle ${key}"><span></span></button>`; }
  function bindSettingsControls() {
    $$('[data-toggle-setting]').forEach(btn=>btn.addEventListener('click',()=>{const key=btn.dataset.toggleSetting;state.settings[key]=!state.settings[key];persist();renderSettings();}));
    $('#themeSelect')?.addEventListener('change',e=>{setTheme(e.target.value);renderSettings();});
    $('#localReset')?.addEventListener('click',()=>{ showModal('Reset local data?',`<p>This removes chats, projects, and preferences saved by this demo in this browser.</p>`,`<button class="secondary-btn" data-modal-close>Cancel</button><button class="danger-btn" id="confirmReset">Reset</button>`); $('#confirmReset').addEventListener('click',()=>{localStorage.removeItem(STORAGE_KEY);state=defaultState();setTheme('light');closeModal();render();toast('Local data cleared.');}); });
    $('#clearChats')?.addEventListener('click',()=>{ showModal('Clear chat history?',`<p>All conversations saved in this browser will be deleted.</p>`,`<button class="secondary-btn" data-modal-close>Cancel</button><button class="danger-btn" id="confirmClearChats">Clear history</button>`); $('#confirmClearChats').addEventListener('click',()=>{state.chats=[];state.activeChatId=null;persist();closeModal();renderSettings();toast('Chat history cleared.');}); });
    $('#testUpload')?.addEventListener('click',()=>filePicker.click());
    $$('[data-connector]').forEach(btn=>btn.addEventListener('click',()=>showModal(`Connect ${btn.dataset.connector}`,`<p>Connector sign-in is handled by the original Claude account service. No credentials are requested or stored by this offline reconstruction.</p>`,`<button class="primary-btn" data-modal-close>Close</button>`)));
    $$('[data-account-action]').forEach(btn=>btn.addEventListener('click',()=>toast('This account feature requires the original Claude service.')));
    $$('[data-page-link]').forEach(btn=>btn.addEventListener('click',()=>navigate(btn.dataset.pageLink)));
  }

  async function gatewayFetch(path, options={}) {
    if(!gatewayToken) throw new Error('Gateway admin token belum diatur. Tekan Configure.');
    const response=await fetch(API_BASE+path,{...options,headers:{'content-type':'application/json','authorization':'Bearer '+gatewayToken,...(options.headers||{})}});
    const data=await response.json().catch(()=>({})); if(!response.ok) throw new Error(data.error||`Gateway error ${response.status}`); return data;
  }
  async function bindProviderSettings(){
    const status=$('#gatewayStatus'),list=$('#providerList');
    try{const h=await fetch(API_BASE+'/health');if(!h.ok)throw new Error('Gateway not reachable');status.textContent='Gateway online · '+API_BASE;}catch{status.textContent='Gateway tidak terjangkau · jalankan backend di Android/Termux.';}
    async function refresh(){try{const d=await gatewayFetch('/v1/providers');list.innerHTML=d.providers.length?d.providers.map(p=>`<div class="setting-row"><div class="setting-copy"><strong>${esc(p.label)}</strong><small>${esc(p.base)} · API key encrypted</small></div><div style="display:flex;gap:6px"><button class="secondary-btn" data-models="${esc(p.id)}">Models</button><button class="danger-btn" data-delete-provider="${esc(p.id)}">Remove</button></div></div>`).join(''):'<p>No provider connected yet.</p>';$$('[data-models]',list).forEach(b=>b.onclick=async()=>{try{const d=await gatewayFetch(`/v1/providers/${b.dataset.models}/models`);showModal('Available models',`<div class="model-list">${d.models.map(m=>`<button class="model-option" data-use-model="${esc(m.id)}" data-provider="${esc(b.dataset.models)}"><div><strong>${esc(m.id)}</strong></div><span class="radio-dot"></span></button>`).join('')}</div>`,`<button class="secondary-btn" data-modal-close>Close</button>`);$$('[data-use-model]').forEach(x=>x.onclick=()=>{state.model=x.dataset.useModel;state.provider=x.dataset.provider;persist();closeModal();render();toast('Model selected');});}catch(e){toast(e.message)}});$$('[data-delete-provider]',list).forEach(b=>b.onclick=async()=>{if(!confirm('Remove this provider and its encrypted key?'))return;try{await gatewayFetch(`/v1/providers/${b.dataset.deleteProvider}`,{method:'DELETE'});refresh();}catch(e){toast(e.message)}});}catch(e){list.innerHTML=`<p>${esc(e.message)}</p>`}}
    $('#gatewayConfig')?.addEventListener('click',()=>{showModal('Configure AI gateway',`<label class="field-label">Gateway URL</label><input class="modal-input" id="gatewayUrl" value="${esc(API_BASE)}"><label class="field-label">Admin token</label><input class="modal-input" id="gatewayAdmin" type="password" placeholder="Gateway ADMIN_TOKEN"><p>Token disimpan hanya di session browser. API keys provider tidak disimpan di browser.</p>`,`<button class="secondary-btn" data-modal-close>Cancel</button><button class="primary-btn" id="saveGateway">Connect</button>`);$('#saveGateway').onclick=()=>{const url=$('#gatewayUrl').value.trim().replace(/\/$/,''),token=$('#gatewayAdmin').value.trim();if(!url||!token)return toast('URL dan admin token wajib diisi');localStorage.setItem('claude-gateway-url',url);sessionStorage.setItem('claude-gateway-token',token);gatewayToken=token;location.reload();};});
    $('#addProvider')?.addEventListener('click',()=>{showModal('Add AI provider',`<label class="field-label">Provider</label><select class="modal-input" id="newProvider"><option value="auto" selected>Auto-detect from API key</option><option value="anthropic">Anthropic Claude</option><option value="openai">OpenAI</option><option value="gemini">Google Gemini</option><option value="openrouter">OpenRouter</option><option value="deepseek">DeepSeek</option><option value="groq">Groq</option><option value="mistral">Mistral</option><option value="xai">xAI</option><option value="custom">Custom OpenAI-compatible</option></select><label class="field-label">API key</label><input class="modal-input" id="newApiKey" type="password" autocomplete="off" placeholder="Paste API key"><label class="field-label">Custom base URL (optional)</label><input class="modal-input" id="newBaseUrl" placeholder="Leave blank for provider default"><p>Key will be encrypted with AES-256-GCM in the gateway vault.</p>`,`<button class="secondary-btn" data-modal-close>Cancel</button><button class="primary-btn" id="saveProvider">Save & test</button>`);$('#saveProvider').onclick=async()=>{let provider=$('#newProvider').value;const apiKey=$('#newApiKey').value,baseUrl=$('#newBaseUrl').value.trim();try{if(provider==='auto'){const detected=await gatewayFetch('/v1/providers/detect',{method:'POST',body:JSON.stringify({apiKey})});if(!detected.detected)throw new Error('Key format is ambiguous. Candidate providers: '+detected.candidates.join(', ')+'. Select the correct provider manually.');provider=detected.detected;}await gatewayFetch('/v1/providers',{method:'POST',body:JSON.stringify({provider,apiKey,...(baseUrl?{baseUrl}:{})})});await gatewayFetch(`/v1/providers/${provider}/test`,{method:'POST',body:'{}'});closeModal();refresh();toast('Provider connected and verified');}catch(e){toast(e.message)}};});
    refresh();
  }

  function showModal(title, body, actions) {
    modalRoot.innerHTML = `<div class="modal-backdrop" id="modalBackdrop"><section class="modal" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="modal-head"><h2>${esc(title)}</h2><button class="icon-btn" data-modal-close aria-label="Close">${icon('close')}</button></div><div class="modal-body">${body}</div><div class="modal-actions">${actions || '<button class="primary-btn" data-modal-close>Close</button>'}</div></section></div>`;
    $$('[data-modal-close]', modalRoot).forEach(btn=>btn.addEventListener('click',closeModal));
    $('#modalBackdrop').addEventListener('click',e=>{if(e.target.id==='modalBackdrop')closeModal();});
    const first = $('.modal-input, .modal-textarea',modalRoot); setTimeout(()=>first?.focus(),20);
  }
  function closeModal() { modalRoot.innerHTML=''; }
  function toast(message) { const old=$('.toast'); if(old)old.remove(); const el=document.createElement('div');el.className='toast';el.textContent=message;document.body.appendChild(el);setTimeout(()=>el.remove(),2500); }
  async function showModelPicker() {
    try {
      const data=await gatewayFetch(`/v1/providers/${state.provider||'anthropic'}/models`);
      const models=data.models.slice(0,120);
      showModal('Choose a model',`<p>Provider: ${esc(state.provider||'anthropic')}. Models are discovered live from its API.</p>${models.map(m=>`<button class="model-option ${state.model===m.id?'selected':''}" data-model="${esc(m.id)}"><div class="model-dot"></div><div><strong>${esc(m.id)}</strong><small>${esc(m.display_name||m.owned_by||'Available model')}</small></div><span class="radio-dot"></span></button>`).join('')}`,`<button class="secondary-btn" data-modal-close>Done</button>`);
      $$('[data-model]').forEach(row=>row.addEventListener('click',()=>{state.model=row.dataset.model;persist();closeModal();render();}));
    } catch(e) { showModal('Choose a model',`<p>${esc(e.message)}</p><p>Connect a provider and discover its models in Settings → AI Providers.</p>`,`<button class="secondary-btn" data-modal-close>Close</button><button class="primary-btn" id="openProviders">Open providers</button>`);$('#openProviders')?.addEventListener('click',()=>{closeModal();state.page='settings';state.settingsSection='providers';render();}); }
  }
  function showAccountMenu() {
    showModal('Local account',`<div style="display:flex;align-items:center;gap:11px;margin:8px 0 15px"><div class="avatar" style="width:38px;height:38px">U</div><div><strong style="font-size:12px">Local account</strong><p style="margin:3px 0 0">Free plan · Demo profile</p></div></div><p>This profile stores data only in this browser. No account is signed in.</p>`,`<button class="secondary-btn" data-modal-close>Close</button><button class="danger-btn" id="accountReset">Reset local data</button>`);
    $('#accountReset')?.addEventListener('click',()=>{closeModal();state.settingsSection='privacy';state.page='settings';render();$('#clearChats')?.click();});
  }
  function navigate(page) { state.page=page; if(page!=='chat')state.activeChatId=null; persist(); closeSidebar(); render(); }

  // Global event bindings
  $$('.nav-item[data-page]').forEach(btn=>btn.addEventListener('click',()=>navigate(btn.dataset.page)));
  $('#newChatBtn').addEventListener('click',newChat); $('#newChatTop').addEventListener('click',newChat);
  $('#openSidebar').addEventListener('click',openSidebar); $('#closeSidebar').addEventListener('click',closeSidebar); $('#sidebarScrim').addEventListener('click',closeSidebar);
  $('#modelTrigger').addEventListener('click',showModelPicker);
  $('#themeToggle').addEventListener('click',()=>setTheme(state.theme==='dark'?'light':'dark'));
  $('#accountButton').addEventListener('click',showAccountMenu); $('#accountButton').addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' ')showAccountMenu();});
  $('#recentMore').addEventListener('click',()=>showModal('Recent chats',`<p>Your chats are saved locally. Use Search to find a conversation by its title or message content.</p>`,`<button class="primary-btn" data-modal-close>Close</button>`));
  document.addEventListener('keydown',e=>{
    if(e.key==='Escape'){closeModal();closeSidebar();}
    if((e.metaKey||e.ctrlKey)&&e.key.toLowerCase()==='k'){e.preventDefault();newChat();}
    if((e.metaKey||e.ctrlKey)&&e.key.toLowerCase()==='f'){e.preventDefault();navigate('search');}
  });
  if ('serviceWorker' in navigator && location.protocol !== 'file:') window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(()=>{}));

  render();
})();
