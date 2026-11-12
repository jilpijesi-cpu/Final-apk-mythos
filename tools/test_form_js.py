# Uji nyata mythos_form.js di Chromium. Jalankan dari akar proyek: python3 tools/test_form_js.py  (butuh playwright + chromium).
import json
from playwright.sync_api import sync_playwright
js=open("app/src/main/assets/mythos_form.js",encoding="utf-8").read()
html="""<!doctype html><body>
<div role="dialog" id="cookie"><button id="ck">Terima cookie</button></div>
<label for="nama">Nama lengkap</label><input id="nama">
<label for="alasan">Alasan</label><textarea id="alasan"></textarea>
<my-card></my-card>
<iframe id="fr" srcdoc="<label for='x'>Nomor tiket</label><input id='x'>"></iframe>
<button id="open" onclick="document.getElementById('m').showModal()">Buka</button>
<dialog id="m"><h2>Konfirmasi data</h2><label for="alasan2">Alasan</label><input id="alasan2"><button id="ok2">Simpan perubahan</button></dialog>
<script>
customElements.define('my-card',class extends HTMLElement{connectedCallback(){const r=this.attachShadow({mode:'open'});r.innerHTML='<label for="kota">Kota kejadian</label><input id="kota"><label><input type="checkbox" id="cb"> Saya setuju</label>';}});
</script></body>"""
def run(pg,a): return json.loads(pg.evaluate(js+"\n;MythosForm.run(%s)"%json.dumps(a)))
with sync_playwright() as p:
    b=p.chromium.launch(); pg=b.new_page(); pg.set_content(html); pg.wait_for_timeout(300)
    r=run(pg,{"op":"inspect"}); print("inspect modal:",r.get("modal"), [f.get("label") for f in r["fields"]][:12])
    print(run(pg,{"op":"fill","label":"Nama lengkap","value":"Budi"}))
    print(run(pg,{"op":"fill","label":"Kota kejadian","value":"Bandung"}))
    print(run(pg,{"op":"fill","label":"Nomor tiket","value":"T-1"}))
    print(run(pg,{"op":"check","label":"Saya setuju","checked":True}))
    print("shadow value:",pg.evaluate("document.querySelector('my-card').shadowRoot.getElementById('kota').value"),
          "cb:",pg.evaluate("document.querySelector('my-card').shadowRoot.getElementById('cb').checked"),
          "iframe:",pg.evaluate("document.getElementById('fr').contentDocument.getElementById('x').value"))
    pg.click("#open"); pg.wait_for_timeout(200)
    r=run(pg,{"op":"inspect"}); print("inspect modal:",r.get("modal"), [f.get("label") for f in r["fields"]])
    print(run(pg,{"op":"fill","label":"Alasan","value":"di modal"}))
    print("bg alasan:",repr(pg.evaluate("document.getElementById('alasan').value")),"modal alasan2:",pg.evaluate("document.getElementById('alasan2').value"))
    print("fill bg while modal:",run(pg,{"op":"fill","label":"Nama lengkap","value":"X"}))
    print("click bg while modal:",run(pg,{"op":"click","label":"Terima cookie","dry":True}))
    print("click modal btn dry:",run(pg,{"op":"click","label":"Simpan perubahan","dry":True}))
    pg.evaluate("document.getElementById('m').close()")
    print("after close, fill bg:",run(pg,{"op":"fill","label":"Alasan","value":"bg"}), repr(pg.evaluate("document.getElementById('alasan').value")))
    b.close()
