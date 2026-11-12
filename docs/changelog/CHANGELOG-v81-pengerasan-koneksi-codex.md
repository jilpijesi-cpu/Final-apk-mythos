# v81 - Perbaikan koneksi Codex + pengerasan

## Bug koneksi
- Backoff persisten kini di-reset setelah sesi sehat (>= 30 dtk); sebelumnya hanya naik sampai 30 dtk.
- Sesi mati dilepas sebelum jeda backoff; permintaan baru menunggu sesi baru, bukan gagal dengan sesi mati.
- Membatalkan permintaan mengirim frame `cancel` -> `turn/interrupt`; sesi Node/Codex tidak lagi dimatikan.
- `request()` menyalakan service bila fase bukan CONNECTED/STARTING (sebelumnya hanya OFF) sehingga bind saja tidak lagi
  meninggalkan service tanpa runner (timeout 90 dtk).
- Mode per-permintaan: pembatalan menutup socket sehingga Codex ikut berhenti.

## Keamanan
- Tool bawaan Codex: argumen `-c features.shell_tool=false` (fail-soft) + pemutus runtime untuk item command/file/MCP/web.
- Thread `ephemeral` (fail-soft) + penghapusan rollout milik thread sendiri di `~/.codex/sessions`.
- Escape tag pembatas prompt (`<USER>`, `</CONVERSATION>`, `<MYTHOS_SYSTEM>`, dst.) di isi pesan.
- Handshake socket: batas 256 karakter dan 10 dtk, perbandingan token waktu-konstan.
- Status Pengaturan menampilkan apakah opsi pembatasan diterima atau runner berjalan dalam mode terbatas.
