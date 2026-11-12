// Mesin pengisian form universal. Dipanggil: MythosForm.run({op, label, value, checked})
(function(){
  const norm = s => (s||'').toLowerCase().replace(/\s+/g,' ').trim();
  const visible = e => !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
  function labelTexts(e){
    const t=[];
    t.push(e.getAttribute('aria-label'));
    const rt=e.getRootNode&&e.getRootNode();
    if(e.id&&rt&&rt.querySelector){ const l=rt.querySelector('label[for="'+CSS.escape(e.id)+'"]'); if(l) t.push(l.innerText); }
    const wrap=e.closest('label'); if(wrap) t.push(wrap.innerText);
    const by=e.getAttribute('aria-labelledby'); if(by&&rt&&rt.querySelector) by.split(/\s+/).forEach(i=>{const x=rt.querySelector('#'+CSS.escape(i)); if(x) t.push(x.innerText);});
    t.push(e.getAttribute('placeholder'), e.getAttribute('title'), e.getAttribute('name'), e.id);
    const grp=e.closest('.form-group,.field,.form-field,.mb-3,.row,td,li,fieldset'); if(grp) t.push((grp.innerText||'').slice(0,160));
    if(e.parentElement) t.push((e.parentElement.innerText||'').slice(0,160));
    return t.map(norm).filter(Boolean);
  }
  function ownLabel(e){
    const t=[e.getAttribute('aria-label')];
    const rt=e.getRootNode&&e.getRootNode();
    if(e.id&&rt&&rt.querySelector){ const l=rt.querySelector('label[for="'+CSS.escape(e.id)+'"]'); if(l) t.push(l.innerText); }
    const wrap=e.closest('label'); if(wrap) t.push(wrap.innerText);
    if(e.getAttribute('role')==='radio') t.push(e.innerText);
    if(e.nextSibling&&e.nextSibling.nodeType===3) t.push(e.nextSibling.textContent);
    return t.map(norm).filter(Boolean);
  }
  function score(e,w){ // makin kecil makin cocok; -1 = tidak cocok
    const ts=labelTexts(e);
    if(ts.slice(0,8).some(x=>x===w)) return 0;
    if(ts.slice(0,8).some(x=>x.includes(w))) return 1;
    if(ts.some(x=>x.includes(w))) return 2;
    return -1;
  }
  function best(list,w){
    let b=null,bs=9;
    for(const e of list){ const s=score(e,w); if(s>=0 && s<bs){b=e;bs=s;} }
    return b;
  }
  function fire(e,types){ types.forEach(t=>e.dispatchEvent(new Event(t,{bubbles:true}))); }
  function setNative(el,v){
    const proto = el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:
                  el instanceof HTMLSelectElement?HTMLSelectElement.prototype:HTMLInputElement.prototype;
    const set=Object.getOwnPropertyDescriptor(proto,'value')?.set; set?set.call(el,v):(el.value=v);
  }
  // Semua akar pencarian: dokumen utama, iframe sama-origin (bersarang), dan shadow root yang terbuka.
  // Shadow root tertutup dan iframe lintas-origin tidak bisa diakses dari skrip halaman.
  function roots(){
    const out=[], seen=new Set();
    (function add(r){
      if(!r||seen.has(r)) return; seen.add(r); out.push(r);
      r.querySelectorAll('*').forEach(e=>{
        if(e.shadowRoot) add(e.shadowRoot);
        if(e.tagName==='IFRAME'){ try{ if(e.contentDocument) add(e.contentDocument); }catch(_){} }
      });
    })(document);
    return out;
  }
  function rawAll(sel){ const r=[]; roots().forEach(d=>{ try{ d.querySelectorAll(sel).forEach(e=>r.push(e)); }catch(_){} }); return r; }
  // Modal yang sedang memblokir halaman: dialog modal, aria-modal, alertdialog, atau .modal Bootstrap yang terbuka.
  // role=dialog TANPA aria-modal (mis. banner cookie) tidak dianggap memblokir.
  function activeModal(){
    const sels=['dialog:modal','[aria-modal="true"]','[role=alertdialog]','.modal.show','.modal.in'];
    let ms=[];
    sels.forEach(q=>{ try{ rawAll(q).forEach(e=>{ if(visible(e)&&!ms.includes(e)) ms.push(e); }); }catch(_){} });
    return ms.length?ms[ms.length-1]:null;
  }
  function inside(m,e){
    let n=e;
    while(n){
      if(m.contains(n)) return true;
      const r=n.getRootNode&&n.getRootNode();
      n = r&&r.host ? r.host : (r&&r.defaultView&&r.defaultView.frameElement ? r.defaultView.frameElement : null);
    }
    return false;
  }
  // Saat ada modal, hanya elemen di dalam modal yang bisa dibaca/diisi/diklik (tidak menembus ke halaman di belakangnya).
  function all(sel){ const m=activeModal(); const l=rawAll(sel); return m?l.filter(e=>inside(m,e)):l; }
  const ok=(m)=>JSON.stringify({ok:true,msg:m}), no=(m)=>JSON.stringify({ok:false,msg:m});

  // Sorotan live: gulir ke elemen yang sedang dikerjakan agen dan beri garis oranye ±1,6 dtk, supaya pengguna bisa memantau.
  function mark(el){
    try{
      try{ el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'}); }catch(_){ el.scrollIntoView(); }
      const o=el.style.outline, f=el.style.outlineOffset;
      el.style.outline='3px solid #E8590C'; el.style.outlineOffset='2px';
      setTimeout(()=>{ try{ el.style.outline=o; el.style.outlineOffset=f; }catch(_){} },1600);
    }catch(_){}
  }
  function fillText(label,value){
    const w=norm(label);
    const inputs=all('input:not([type=checkbox]):not([type=radio]):not([type=file]):not([type=hidden]):not([type=submit]):not([type=button]),textarea,[contenteditable=""],[contenteditable=true],[role=textbox]').filter(visible);
    const el=best(inputs,w);
    if(el){
      mark(el);
      el.focus();
      if(el.isContentEditable||el.getAttribute('role')==='textbox'){ el.innerText=value; fire(el,['input','change']); }
      else { setNative(el,value); fire(el,['input','change','blur']); }
      return ok('terisi: '+label);
    }
    // editor rich-text berbasis iframe (TinyMCE/CKEditor 4): hanya bila label cocok dengan konteks iframe
    for(const f of document.querySelectorAll('iframe')){
      try{ const d=f.contentDocument;
        if(d&&d.body&&d.body.isContentEditable){
          const ctx=[f.getAttribute('title'),f.getAttribute('aria-label'),f.id,(f.parentElement&&f.parentElement.innerText||'').slice(0,160)].map(norm).join(' ');
          if(!ctx.includes(w)) continue;
          d.body.innerText=value; fire(d.body,['input']); return ok('editor iframe terisi: '+label);
        } }catch(_){}
    }
    return no('field tidak ditemukan: '+label);
  }
  function choose(label,value){
    const w=norm(label), v=norm(value);
    const sels=all('select').filter(visible);
    const el=best(sels,w);
    if(el){
      mark(el);
      const opt=[...el.options].find(o=>norm(o.text)===v)||[...el.options].find(o=>norm(o.text).includes(v))||[...el.options].find(o=>norm(o.value)===v);
      if(!opt) return no('opsi tidak ada. Pilihan: '+[...el.options].map(o=>o.text.trim()).filter(Boolean).slice(0,15).join(' | '));
      setNative(el,opt.value); el.selectedIndex=opt.index; fire(el,['input','change']); return ok('dipilih: '+opt.text.trim());
    }
    // dropdown kustom (combobox/ARIA): buka lalu klik opsi
    const trig=best(all('[role=combobox],[aria-haspopup=listbox],[aria-haspopup=true],.select2-selection,.dropdown-toggle').filter(visible),w);
    if(trig){
      mark(trig);
      trig.click();
      return JSON.stringify({ok:true,pending:true,msg:'dropdown dibuka; panggil pickOption'});
    }
    return no('dropdown tidak ditemukan: '+label);
  }
  function pickOption(value){
    const v=norm(value);
    const opts=all('[role=option],li.select2-results__option,.dropdown-menu a,.dropdown-item,ul[role=listbox] li').filter(visible);
    const o=opts.find(e=>norm(e.innerText)===v)||opts.find(e=>norm(e.innerText).includes(v));
    if(!o) return no('opsi kustom tidak ada. Tersedia: '+opts.map(e=>norm(e.innerText)).slice(0,15).join(' | '));
    o.click(); return ok('dipilih: '+norm(o.innerText));
  }
  function check(label,checked){
    const w=norm(label);
    const boxes=all('input[type=checkbox],[role=checkbox],[role=switch]').filter(e=>visible(e)||e.tagName==='INPUT');
    const el=best(boxes,w);
    if(!el) return no('checkbox tidak ditemukan: '+label);
    mark(el);
    const cur = el.tagName==='INPUT'?el.checked:el.getAttribute('aria-checked')==='true';
    if(cur!==checked) el.click();
    const now = el.tagName==='INPUT'?el.checked:el.getAttribute('aria-checked')==='true';
    return now===checked?ok((checked?'dicentang: ':'dihapus centang: ')+label):no('status tidak berubah (komponen kustom?)');
  }
  function radio(label,value){
    const w=norm(label), v=norm(value);
    const rs=all('input[type=radio],[role=radio]');
    let cand=rs.filter(r=>ownLabel(r).some(x=>x===v)); if(!cand.length) cand=rs.filter(r=>ownLabel(r).some(x=>x.includes(v)));
    let el=null;
    if(w) el=cand.find(r=>{const g=r.closest('fieldset,[role=radiogroup],.form-group,.field'); return g&&norm(g.innerText).includes(w);});
    el=el||cand[0];
    if(!el) return no('radio tidak ditemukan: '+value);
    mark(el);
    el.click(); return ok('radio dipilih: '+value);
  }
  function date(label,iso){ // iso: yyyy-MM-dd
    const w=norm(label);
    const el=best(all('input[type=date],input[type=datetime-local],input[type=text],input:not([type])').filter(visible),w);
    if(!el) return no('kolom tanggal tidak ditemukan: '+label);
    mark(el);
    let v=iso;
    if(el.type!=='date'){ const m=/^(\d{4})-(\d{2})-(\d{2})$/.exec(iso); const ph=norm(el.getAttribute('placeholder'));
      if(m) v = /mm\/dd/.test(ph)?`${m[2]}/${m[3]}/${m[1]}`: /yyyy-mm-dd/.test(ph)?iso : `${m[3]}/${m[2]}/${m[1]}`; }
    el.focus(); setNative(el,v); fire(el,['input','change','blur']);
    return (el.value&&el.value.length>0)?ok('tanggal terisi: '+v):no('tanggal ditolak oleh komponen (datepicker kustom)');
  }
  function isSubmitEl(e){ // tombol yang mengirim formulir: <input type=submit>, atau <button> tanpa type di dalam <form>
    const t=(e.tagName||'').toLowerCase(), ty=(e.getAttribute('type')||'').toLowerCase();
    if(t==='input') return ty==='submit'||ty==='image';
    if(t==='button') return ty==='submit'||(ty===''&&!!e.form);
    return false;
  }
  function click(label,dry,useTarget){
    const txt=e=>norm(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title'));
    let el=null;
    const lt=window.__mythosTarget; if(useTarget&&lt&&lt.isConnected&&visible(lt)) el=lt; // elemen yang sama dengan hasil dry-run
    if(!el){
      const w=norm(label);
      const els=all('button,input[type=submit],input[type=button],a,[role=button],[role=link],[role=menuitem]').filter(visible);
      el=els.find(e=>txt(e)===w)||els.find(e=>txt(e).includes(w));
    }
    if(!el) return no('tombol tidak ditemukan: '+label);
    const sub=isSubmitEl(el);
    mark(el);
    if(dry){ window.__mythosTarget=el; return JSON.stringify({ok:true,dry:true,text:txt(el),submit:sub,msg:'siap diklik: '+txt(el)}); }
    el.click(); return ok('diklik: '+txt(el));
  }
  const SENS=/pass|sandi|\bpin\b|otp|cvv|cvc|captcha|token|kartu|card|rekening|\bnik\b|ktp|npwp/i;
  function inspect(){ // peta field untuk dikirim ke LLM (termasuk apa yang SUDAH terisi, supaya agen tidak mengisi ulang)
    const rows=[];
    all('input,select,textarea,[role=combobox],[role=checkbox],[role=radio],[contenteditable=true],button,[role=button]').filter(visible).forEach((e,i)=>{
      const tag=e.tagName.toLowerCase(), type=e.getAttribute('type')||e.getAttribute('role')||'';
      if(type==='hidden') return;
      const isBtn = tag==='button' || type==='button' || type==='submit' || type==='image' || e.getAttribute('role')==='button';
      let lab=labelTexts(e)[0]||labelTexts(e).find(x=>x.length<80)||'';
      if(isBtn){ const own=norm(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||''); if(own) lab=own; }
      const o={i,tag,type,label:lab.slice(0,80)};
      const sens=SENS.test(lab+' '+(e.getAttribute('name')||'')+' '+(e.id||'')+' '+type);
      if(e.disabled) o.disabled=true;
      if(tag==='select'){
        o.options=[...e.options].map(x=>x.text.trim()).slice(0,20);
        const so=e.options[e.selectedIndex]; if(so&&so.value!=='') o.selected=so.text.trim().slice(0,60);
      } else if(type==='checkbox'||type==='radio'){
        o.checked = tag==='input' ? !!e.checked : e.getAttribute('aria-checked')==='true';
      } else if(!isBtn && (tag==='input'||tag==='textarea'||e.isContentEditable)){
        const v = (tag==='input'||tag==='textarea') ? e.value : (e.innerText||'');
        if(v){ if(sens||type==='password'||type==='file') o.filled=true; else o.value=String(v).slice(0,80); }
      }
      if(e.required) o.required=true;
      rows.push(o);
    });
    document.querySelectorAll('iframe').forEach(f=>{try{ if(!f.contentDocument) rows.push({tag:'iframe',note:'lintas-origin, tidak bisa diakses'});}catch(_){rows.push({tag:'iframe',note:'lintas-origin, tidak bisa diakses'});}});
    const m=activeModal();
    const out={ok:true,fields:rows.slice(0,120)};
    if(m) out.modal=norm((m.getAttribute('aria-label')||(m.querySelector('h1,h2,h3,[role=heading],.modal-title')||{}).innerText||m.innerText||'')).slice(0,120)||'(tanpa judul)';
    return JSON.stringify(out);
  }
  window.MythosForm={
    run(a){ try{
      switch(a.op){
        case 'fill': return fillText(a.label,a.value);
        case 'choose': return choose(a.label,a.value);
        case 'pick': return pickOption(a.value);
        case 'check': return check(a.label,a.checked!==false);
        case 'radio': return radio(a.label||'',a.value);
        case 'date': return date(a.label,a.value);
        case 'click': return click(a.label,a.dry===true,a.target===true);
        case 'inspect': return inspect();
        default: return no('op tidak dikenal');
      } }catch(e){ return no('galat: '+e.message); } }
  };
})();
