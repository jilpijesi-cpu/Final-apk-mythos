# v82 - Tindak lanjut audit: pengerasan Codex fail-closed

## Keamanan
- Runner Codex (sekali-jalan dan persisten) tidak lagi mengulang tanpa `-c features.shell_tool=false` bila Codex menolak opsi itu.
  Sesi berhenti dengan galat `Codex menolak opsi pengerasan ...` (tidak dapat diulang otomatis). Sebelumnya mode longgar bergantung pada
  pemutus yang baru bereaksi setelah item dimulai.
- `child.stdin` runner kini punya handler `error`: tanpa itu, Codex yang keluar cepat (mis. menolak argumen) membuat node crash dengan EPIPE
  sebelum galat fail-closed sempat dikirim.

## Keandalan
- `app_server.js` ditulis ke berkas sementara unik (`app_server.js.$$`) lalu `mv -f` atomik; permintaan paralel tidak lagi membaca berkas terpotong.

## Tes
- `CodexHardeningTest`: tidak boleh ada `hardened = false`; wajib ada pesan fail-closed dan handler EPIPE.
- `TermuxLauncherTest`: perintah menulis runner lewat berkas sementara + `mv -f`.

## Belum dikerjakan dari audit
No. 2 (hapus jalur always-on yang mati), 3 (pembatalan cadangan + TTL berkas live), 5 (OAuth state), 6 (versi/CI).
