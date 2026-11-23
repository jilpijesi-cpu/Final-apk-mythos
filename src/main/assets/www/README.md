# Form Input Data KTP Mobile (HTML5 `<canvas>` Berbasis Koordinat)

Versi modular aplikasi mobile dengan pratinjau dan ekspor kartu menggunakan **HTML5 `<canvas>` berbasis koordinat `(x, y)`** tanpa dependensi `html2canvas`.

## Struktur Modul
- `index.html` — Antarmuka mobile 4 langkah, elemen `<canvas id="ktpCanvas" width="1011" height="638">`, kontrol unggah template kustom, tab Tersimpan, dan tab Panduan.
- `css/style.css` — Gaya mobile shell, animasi transisi, dan safe-area.
- `js/canvas-renderer.js` — Konfigurasi koordinat (`CARD_CANVAS_CONFIG`), generator template bawaan, penulisan teks via `ctx.fillText`, pemotongan pas foto proporsional (`ctx.drawImage`), dan ekspor `canvas.toDataURL('image/png')`.
- `js/wilayah-data.js` — Database 38 provinsi Indonesia (kode Kemendagri).
- `js/nik-parser.js` — Parser 16 digit NIK dengan validasi kalender & tahun kabisat.
- `js/storage.js` — Penyimpanan data lokal (`localStorage` + fallback memori).
- `js/app.js` — Pengelola state formulir, validasi langkah, template kustom, dan ekspor JSON/CSV/PNG.
