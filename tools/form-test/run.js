const {chromium}=require('/opt/npm-tools/node_modules/playwright');
const fs=require('fs');
(async()=>{
  const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium-1243/chrome-linux64/chrome',args:['--no-sandbox']});
  const p=await b.newPage(); await p.goto('file://'+process.cwd()+'/test.html'); await p.waitForTimeout(300);
  await p.addScriptTag({content:fs.readFileSync('formtools.js','utf8')});
  const R=async a=>JSON.parse(await p.evaluate(a=>MythosForm.run(a),a));
  const T=[
   [{op:'fill',label:'nama lengkap',value:'Budi'},async()=>await p.inputValue('#nama')==='Budi'],
   [{op:'fill',label:'Usulan Penyelesaian',value:'Kembalikan dana'},async()=>await p.inputValue('#u')==='Kembalikan dana'],
   [{op:'fill',label:'Uraian Kronologi',value:'Kronologi X'},async()=>(await p.innerText('#rt'))==='Kronologi X'],
   [{op:'choose',label:'Jenis Pengaduan',value:'Asuransi'},async()=>await p.inputValue('#jenis')==='2'],
   [{op:'choose',label:'Jenis Pengaduan',value:'Tidak ada'},async()=>true,false],
   [{op:'check',label:'menyetujui syarat',checked:true},async()=>await p.isChecked('#setuju')],
   [{op:'radio',label:'Status Pengadu',value:'Badan Hukum'},async()=>await p.isChecked('input[value=b]')],
   [{op:'date',label:'Tanggal Kejadian',value:'2026-11-08'},async()=>await p.inputValue('#tgl')==='2026-11-08'],
   [{op:'date',label:'Tanggal Surat',value:'2026-11-08'},async()=>await p.inputValue('#tgl2')==='08/11/2026'],
   [{op:'choose',label:'Provinsi',value:'Jawa Barat'},async()=>true,true],
   [{op:'pick',value:'Jawa Barat'},async()=>(await p.getAttribute('#lb','data-v'))==='Jawa Barat'],
   [{op:'fill',label:'zzz tidak ada',value:'x'},async()=>true,false],
   [{op:'fill',label:'Isi Dokumen',value:'Teks editor'},async()=>true,true],
  ];
  let fail=0;
  for(const [a,chk,expectOk] of T){
    const r=await R(a); const exp=expectOk===undefined?true:expectOk;
    const good=(r.ok===exp)&&await chk();
    if(!good)fail++; console.log(good?'PASS':'FAIL',a.op,a.label||'',a.value||'','->',r.msg);
  }
  let r=await R({op:'click',label:'kirim',dry:true});
  const d1=r.ok&&r.dry&&r.text==='kirim tanggapan'&&!(await p.evaluate(()=>window.__c)); console.log(d1?'PASS':'FAIL','click dry (tanpa efek)',r.text); if(!d1)fail++;
  r=await R({op:'click',label:'Kirim Tanggapan'});
  const d2=r.ok&&(await p.evaluate(()=>window.__c))===1; console.log(d2?'PASS':'FAIL','click nyata'); if(!d2)fail++;
  r=await R({op:'click',label:'lanjut',dry:true});
  const d3=r.ok&&r.submit===true&&!(await p.evaluate(()=>window.__s)); console.log(d3?'PASS':'FAIL','dry: <button> tanpa type dalam form = submit',r.text,r.submit); if(!d3)fail++;
  r=await R({op:'click',label:'lanjut aman',dry:true});
  const d4=r.ok&&r.submit===false; console.log(d4?'PASS':'FAIL','dry: type=button di luar form bukan submit'); if(!d4)fail++;
  r=await R({op:'click',label:'cetak',dry:true});
  await p.evaluate(()=>{const b=document.getElementById('d1');b.parentNode.insertBefore(document.getElementById('d2'),b);}); // urutan DOM berubah antara dry-run dan klik
  r=await R({op:'click',label:'cetak',target:true});
  const d5=await p.evaluate(()=>window.__d1===1&&window.__d2===undefined); console.log(d5?'PASS':'FAIL','klik target = elemen hasil dry-run (bukan tombol kembar)'); if(!d5)fail++;
  console.log(JSON.stringify(await R({op:'inspect'})).slice(0,300));
  console.log(fail?fail+' GAGAL':'SEMUA LULUS'); await b.close();
})();
