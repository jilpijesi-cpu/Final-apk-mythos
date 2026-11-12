// Mesin pengisian form universal. Dipanggil: MythosForm.run({op, label, value, checked})
(function(){
  const norm = s => (s||'').toLowerCase().replace(/\s+/g,' ').trim();
  const visible = e => !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
  function labelTexts(e){
    const t=[];
    t.push(e.getAttribute('aria-label'));
    if(e.id){ const l=document.querySelector('label[for="'+CSS.escape(e.id)+'"]'); if(l) t.push(l.innerText); }
    const wrap=e.closest('label'); if(wrap) t.push(wrap.innerText);
    const by=e.getAttribute('aria-labelledby'); if(by) by.split(/\s+/).forEach(i=>{const x=document.getElementById(i); if(x) t.push(x.innerText);});
    t.push(e.getAttribute('placeholder'), e.getAttribute('title'), e.getAttribute('name'), e.id);
    const grp=e.closest('.form-group,.field,.form-field,.mb-3,.row,td,li,fieldset'); if(grp) t.push((grp.innerText||'').slice(0,160));
    if(e.parentElement) t.push((e.parentElement.innerText||'').slice(0,160));
    return t.map(norm).filter(Boolean);
  }
  function ownLabel(e){
    const t=[e.getAttribute('aria-label')];
    if(e.id){ const l=document.querySelector('label[for="'+CSS.escape(e.id)+'"]'); if(l) t.push(l.innerText); }
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
  function docs(){ // dokumen utama + iframe sama-origin
    const out=[document];
    document.querySelectorAll('iframe').forEach(f=>{ try{ if(f.contentDocument) out.push(f.contentDocument);}catch(_){} });
    return out;
  }
  function all(sel){ const r=[]; docs().forEach(d=>d.querySelectorAll(sel).forEach(e=>r.push(e))); return r; }
  const ok=(m)=>JSON.stringify({ok:true,msg:m}), no=(m)=>JSON.stringify({ok:false,msg:m});

  function fillText(label,value){
    const w=norm(label);
    const inputs=all('input:not([type=checkbox]):not([type=radio]):not([type=file]):not([type=hidden]):not([type=submit]):not([type=button]),textarea,[contenteditable=""],[contenteditable=true],[role=textbox]').filter(visible);
    const el=best(inputs,w);
    if(el){
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
      const opt=[...el.options].find(o=>norm(o.text)===v)||[...el.options].find(o=>norm(o.text).includes(v))||[...el.options].find(o=>norm(o.value)===v);
      if(!opt) return no('opsi tidak ada. Pilihan: '+[...el.options].map(o=>o.text.trim()).filter(Boolean).slice(0,15).join(' | '));
      setNative(el,opt.value); el.selectedIndex=opt.index; fire(el,['input','change']); return ok('dipilih: '+opt.text.trim());
    }
    // dropdown kustom (combobox/ARIA): buka lalu klik opsi
    const trig=best(all('[role=combobox],[aria-haspopup=listbox],[aria-haspopup=true],.select2-selection,.dropdown-toggle').filter(visible),w);
    if(trig){
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
    el.click(); return ok('radio dipilih: '+value);
  }
  function date(label,iso){ // iso: yyyy-MM-dd
    const w=norm(label);
    const el=best(all('input[type=date],input[type=datetime-local],input[type=text],input:not([type])').filter(visible),w);
    if(!el) return no('kolom tanggal tidak ditemukan: '+label);
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
    if(dry){ window.__mythosTarget=el; return JSON.stringify({ok:true,dry:true,text:txt(el),submit:sub,msg:'siap diklik: '+txt(el)}); }
    el.click(); return ok('diklik: '+txt(el));
  }
  function inspect(){ // peta field untuk dikirim ke LLM
    const rows=[];
    all('input,select,textarea,[role=combobox],[role=checkbox],[role=radio],[contenteditable=true],button,[role=button]').filter(visible).forEach((e,i)=>{
      const tag=e.tagName.toLowerCase(), type=e.getAttribute('type')||e.getAttribute('role')||'';
      if(type==='hidden') return;
      const lab=labelTexts(e)[0]||labelTexts(e).find(x=>x.length<80)||'';
      const o={i,tag,type,label:lab.slice(0,80)};
      if(tag==='select') o.options=[...e.options].map(x=>x.text.trim()).slice(0,20);
      if(e.required) o.required=true;
      rows.push(o);
    });
    document.querySelectorAll('iframe').forEach(f=>{try{ if(!f.contentDocument) rows.push({tag:'iframe',note:'lintas-origin, tidak bisa diakses'});}catch(_){rows.push({tag:'iframe',note:'lintas-origin, tidak bisa diakses'});}});
    return JSON.stringify({ok:true,fields:rows.slice(0,120)});
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
