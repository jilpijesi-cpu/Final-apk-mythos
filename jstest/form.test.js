// Tes mythos_form.js di Chromium sungguhan (Playwright). Jalankan: npm i playwright && node jstest/form.test.js
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');
const SCRIPT = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/mythos_form.js'), 'utf8');

let pass = 0, fail = 0;
async function main() {
  const browser = await chromium.launch();
  async function page(html) {
    const p = await browser.newPage();
    await p.setContent(html);
    return p;
  }
  const run = async (p, args) => JSON.parse(await p.evaluate(`${SCRIPT}\n;MythosForm.run(${JSON.stringify(args)});`));
  async function t(name, fn) {
    try { await fn(); pass++; console.log('ok   ', name); }
    catch (e) { fail++; console.log('GAGAL', name, '-', e.message); }
  }
  const eq = (a, b, m) => { if (a !== b) throw new Error(`${m || ''} diharapkan ${JSON.stringify(b)}, dapat ${JSON.stringify(a)}`); };
  const yes = (c, m) => { if (!c) throw new Error(m || 'kondisi salah'); };

  await t('fill lewat label[for] + event input terpicu', async () => {
    const p = await page('<label for=n>Nama Lengkap</label><input id=n><script>window.ev=[];n.addEventListener("input",()=>ev.push("input"))</script>');
    const r = await run(p, { op: 'fill', label: 'nama lengkap', value: 'Budi' });
    yes(r.ok, r.msg); eq(await p.inputValue('#n'), 'Budi');
    eq(await p.evaluate('ev.length'), 1, 'event input');
  });

  await t('fill textarea dan placeholder', async () => {
    const p = await page('<textarea placeholder="Uraian Pengaduan"></textarea>');
    const r = await run(p, { op: 'fill', label: 'Uraian pengaduan', value: 'Dana belum kembali' });
    yes(r.ok, r.msg); eq(await p.inputValue('textarea'), 'Dana belum kembali');
  });

  await t('fill memilih field paling cocok, bukan yang pertama', async () => {
    const p = await page('<label>Nama Bank <input id=a></label><label>Nama <input id=b></label>');
    const r = await run(p, { op: 'fill', label: 'nama', value: 'X' });
    yes(r.ok, r.msg); eq(await p.inputValue('#b'), 'X'); eq(await p.inputValue('#a'), '');
  });

  await t('fill: field tidak ada -> ok=false', async () => {
    const p = await page('<input aria-label="Email">');
    const r = await run(p, { op: 'fill', label: 'telepon', value: '1' });
    eq(r.ok, false);
  });

  await t('choose <select> persis dan sebagian', async () => {
    const p = await page('<label for=s>Jenis Pengaduan</label><select id=s><option>-</option><option>Perbankan</option><option>Asuransi Jiwa</option></select>');
    const r = await run(p, { op: 'choose', label: 'jenis pengaduan', value: 'asuransi' });
    yes(r.ok, r.msg); eq(await p.inputValue('#s'), 'Asuransi Jiwa');
  });

  await t('choose: opsi tidak ada -> daftar pilihan di pesan', async () => {
    const p = await page('<label for=s>Jenis</label><select id=s><option>A</option><option>B</option></select>');
    const r = await run(p, { op: 'choose', label: 'jenis', value: 'zzz' });
    eq(r.ok, false); yes(r.msg.includes('A') && r.msg.includes('B'), r.msg);
  });

  await t('choose dropdown kustom: pending lalu pick', async () => {
    const p = await page(`<div role=combobox aria-label="Provinsi" onclick="document.getElementById('l').hidden=false">Pilih</div>
      <ul id=l hidden role=listbox><li role=option onclick="window.picked=this.innerText">Jawa Barat</li><li role=option>Bali</li></ul>`);
    const r1 = await run(p, { op: 'choose', label: 'provinsi', value: 'Bali' });
    yes(r1.ok && r1.pending, JSON.stringify(r1));
    const r2 = await run(p, { op: 'pick', value: 'Bali' });
    yes(r2.ok, r2.msg);
  });

  await t('check / uncheck checkbox', async () => {
    const p = await page('<label><input type=checkbox id=c> Saya bersedia dihubungi</label>');
    let r = await run(p, { op: 'check', label: 'bersedia dihubungi', checked: true });
    yes(r.ok, r.msg); eq(await p.isChecked('#c'), true);
    r = await run(p, { op: 'check', label: 'bersedia dihubungi', checked: false });
    yes(r.ok, r.msg); eq(await p.isChecked('#c'), false);
  });

  await t('radio dalam grup yang tepat', async () => {
    const p = await page(`<fieldset>Status Pengadu <label><input type=radio name=a id=a1> Perorangan</label><label><input type=radio name=a id=a2> Badan Hukum</label></fieldset>
      <fieldset>Pelapor <label><input type=radio name=b id=b1> Perorangan</label></fieldset>`);
    const r = await run(p, { op: 'radio', label: 'status pengadu', value: 'perorangan' });
    yes(r.ok, r.msg); eq(await p.isChecked('#a1'), true); eq(await p.isChecked('#b1'), false);
  });

  await t('date: input type=date dan teks dengan placeholder dd/mm/yyyy', async () => {
    const p = await page('<label for=d>Tanggal Kejadian</label><input type=date id=d><input id=t placeholder="Tanggal Lapor (dd/mm/yyyy)">');
    let r = await run(p, { op: 'date', label: 'tanggal kejadian', value: '2026-11-08' });
    yes(r.ok, r.msg); eq(await p.inputValue('#d'), '2026-11-08');
    r = await run(p, { op: 'date', label: 'tanggal lapor', value: '2026-11-08' });
    yes(r.ok, r.msg); eq(await p.inputValue('#t'), '08/11/2026');
  });

  await t('click dry-run: mengenali tombol submit di dalam form, tidak mengklik', async () => {
    const p = await page('<form onsubmit="window.sent=1;return false"><button>Kirim Pengaduan</button></form><button id=x>Lihat</button>');
    const r = await run(p, { op: 'click', label: 'kirim pengaduan', dry: true });
    yes(r.ok && r.submit === true, JSON.stringify(r)); eq(r.text, 'kirim pengaduan');
    eq(await p.evaluate('window.sent||0'), 0, 'tidak boleh terklik saat dry-run');
    const r2 = await run(p, { op: 'click', label: 'lihat', dry: true });
    eq(r2.submit, false);
  });

  await t('click target=true mengklik elemen yang sama dengan hasil dry-run', async () => {
    const p = await page('<button onclick="window.who=1">Lanjut</button><button onclick="window.who=2">Lanjut Sekarang</button>');
    const d = await run(p, { op: 'click', label: 'lanjut', dry: true });
    yes(d.ok, d.msg);
    const r = await run(p, { op: 'click', label: d.text, target: true });
    yes(r.ok, r.msg); eq(await p.evaluate('window.who'), 1);
  });

  await t('modal memblokir elemen di belakangnya', async () => {
    const p = await page('<input aria-label="Nama"><div role=dialog aria-modal=true style="position:fixed;top:0"><input id=in aria-label="Alasan"><button>Tutup</button></div>');
    let r = await run(p, { op: 'fill', label: 'nama', value: 'X' });
    eq(r.ok, false, 'field di belakang modal tidak boleh terisi');
    r = await run(p, { op: 'fill', label: 'alasan', value: 'Y' });
    yes(r.ok, r.msg); eq(await p.inputValue('#in'), 'Y');
  });

  await t('shadow DOM terbuka dapat diisi', async () => {
    const p = await page('<div id=h></div><script>const s=h.attachShadow({mode:"open"});s.innerHTML="<label>Alamat <input id=ai></label>"</script>');
    const r = await run(p, { op: 'fill', label: 'alamat', value: 'Jl. Mawar' });
    yes(r.ok, r.msg); eq(await p.evaluate('h.shadowRoot.getElementById("ai").value'), 'Jl. Mawar');
  });

  await t('iframe sama-origin dapat diisi', async () => {
    const p = await page('<iframe srcdoc="<label>Kota <input id=k></label>"></iframe>');
    await p.waitForTimeout(300);
    const r = await run(p, { op: 'fill', label: 'kota', value: 'Bandung' });
    yes(r.ok, r.msg);
    eq(await p.frames()[1].inputValue('#k'), 'Bandung');
  });

  await t('inspect: memuat password dan tidak memuat hidden; ada options select', async () => {
    const p = await page('<input type=hidden name=csrf><label>Sandi <input type=password></label><select aria-label="Jenis"><option>A</option><option>B</option></select>');
    const r = await run(p, { op: 'inspect' });
    yes(r.ok);
    yes(r.fields.some(f => f.type === 'password'), 'password harus terdeteksi (dipakai needsLogin)');
    yes(!r.fields.some(f => f.type === 'hidden'));
    yes(r.fields.find(f => f.tag === 'select').options.length === 2);
  });

  await t('inspect: nilai terisi tampil, data sensitif disamarkan, centang & teks tombol ikut', async () => {
    const p = await page('<label>Nama <input id=n value="Budi"></label><label>NIK <input id=k value="3171000000000001"></label><label><input type=checkbox id=c checked> Newsletter</label><select aria-label="Jenis"><option value="">-</option><option value=a>Asuransi</option></select><button type=button>Lanjut ke langkah 2</button>');
    await p.selectOption('select', 'a');
    const r = await run(p, { op: 'inspect' });
    const nama = r.fields.find(f => f.label.includes('nama'));
    eq(nama.value, 'Budi', 'nilai nama');
    const nik = r.fields.find(f => f.label.includes('nik'));
    yes(nik.filled === true && nik.value === undefined, 'NIK tidak boleh dikirim ke model');
    eq(r.fields.find(f => f.type === 'checkbox').checked, true, 'checked');
    eq(r.fields.find(f => f.tag === 'select').selected, 'Asuransi', 'selected');
    yes(r.fields.some(f => f.tag === 'button' && f.label === 'lanjut ke langkah 2'), 'teks tombol sebagai label');
  });

  await t('sorotan live: elemen yang diisi diberi outline sementara', async () => {
    const p = await page('<label>Kota <input id=k></label>');
    const r = await run(p, { op: 'fill', label: 'kota', value: 'Bandung' });
    yes(r.ok, r.msg);
    yes((await p.evaluate('document.getElementById("k").style.outline')).includes('3px'), 'outline terpasang');
    await p.waitForTimeout(1900);
    eq(await p.evaluate('document.getElementById("k").style.outline'), '', 'outline dilepas');
  });

  await t('op tidak dikenal -> ok=false', async () => {
    const p = await page('<p>x</p>');
    eq((await run(p, { op: 'hapusSemua' })).ok, false);
  });

  await browser.close();
  console.log(`\n${pass} lulus, ${fail} gagal`);
  process.exit(fail ? 1 : 0);
}
main().catch(e => { console.error(e); process.exit(2); });
