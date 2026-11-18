# Panduan alat agen (selalu tersedia)

Isi pokok panduan ini juga dikirim ke agen di setiap prompt sistem (`ToolDocs.REFERENCE` di ToolGuidance.kt),
jadi agen tidak perlu menebak API alat.

## Satu-satunya antarmuka alat
Alat dipanggil lewat function-call native model. Tidak ada modul, paket, atau API Python bernama `tool_api`.
`run_command` hanya untuk perintah shell; memanggil alat di dalamnya ditolak dengan petunjuk (lihat `detectToolCodeInShell`).

| Alat | Parameter | Catatan |
|---|---|---|
| run_command | deskripsi, command | satu perintah bash |
| write_file | deskripsi, path, content | tulis file utuh |
| create_android_project | deskripsi, folder, nama, package | proyek baru |
| validate_project | deskripsi, folder | hanya membaca; jalankan SEBELUM build_apk |
| build_apk | deskripsi, folder | build di GitHub Actions |
| self_audit | deskripsi, folder | folder = DIREKTORI akar kode, bukan file; audit statis |
| self_upgrade | deskripsi, folder, tahap | mulai, periksa, pasang, pulihkan |
| ask_user | pertanyaan, pilihan | tanya pengguna |

Parameter `folder` yang berisi path file (mis. `app/build.gradle.kts`) ditolak sebelum alat jalan, dengan contoh yang benar.

## Peta path (relatif terhadap folder kerja ~/MYTHOS)
- `projects/aktif/<nama-kebab-case>`: proyek yang dikerjakan
- `projects/arsip`: proyek lama
- `reference/`: source dan alat resmi, hanya baca atau salin
- `attachments/`: lampiran pengguna
- `logs/error_summary.log`: ringkasan galat alat

Bila ragu lokasi: `pwd && ls`. Galat "No such file or directory" otomatis diberi diagnosis berisi peta ini.

## self_audit: apa yang (tidak) dilakukan
Audit STATIS: rahasia di kode, manifest, izin, dependensi, celah tes, fungsi pengaman, status aplikasi terpasang.
Tidak membangun, tidak menjalankan tes, bukan pemindaian keamanan dinamis, tidak memeriksa checksum atau tanda tangan APK.
Batas waktu 900 dtk. Progres tampil live di kartu langkah (`KITA_PROGRESS|jam|n/8 tahap`), dan hasil memuat durasi audit.

## Template hasil-uji.md
```
# Hasil uji

| Tugas | Langkah | Hasil | Bukti |
|---|---|---|---|
| T1 audit-awal | 2 | lulus | hasil-audit |
| T2 build-apk | 5 | gagal | log workflow |
```
- Tugas: `Tn judul-kebab-case`, sama persis di semua baris (`HasilUji.taskLabel`).
- Langkah: jumlah pemanggilan alat pada tugas itu, yang gagal ikut dihitung, jawaban chat tidak. Salin dari log.

## Galat GitHub pada build_apk
| Gejala | Arti | Tindakan |
|---|---|---|
| HTTP 401 / Bad credentials | token salah, dicabut, kedaluwarsa | buat token baru, isi di Pengaturan |
| HTTP 403 | token valid tetapi izin kurang | klasik: scope `repo` + `workflow`; fine-grained: Contents, Actions, Workflows (baca/tulis) |
| 403 SAML | organisasi memakai SSO | otorisasi token ke organisasi |
| 403 rate limit | batas laju API | tunggu beberapa menit |
| tanpa kode HTTP | tidak ada koneksi ke api.github.com | cek internet |

Skrip berhenti segera pada 401/403 (tidak menunggu sia-sia) dan tidak pernah mencetak token.

## Alat buatan agen (create_tool / delete_tool)
Agen bebas membuat alat sendiri bila kekurangan alat: `create_tool(deskripsi, nama, penjelasan, parameter, skrip)`.
- Membuat alat TIDAK meminta persetujuan. Alat disimpan di pengaturan aplikasi dan muncul sebagai function-call baru mulai langkah berikutnya.
- Menjalankan alat lewat jalur `run_command` yang sama: kebijakan perintah, sandbox, mode hanya baca, persetujuan sesuai pengaturan (mis. Mode otonom), dan folder `reference` terkunci tetap berlaku. Isi skrip tampil apa adanya di dialog persetujuan.
- Parameter dibaca sebagai `$1`, `$2`, ... dan `ARG_NAMA`. Maks 20 alat, 6 parameter, 8000 karakter skrip. Nama alat bawaan tidak bisa ditimpa. Nama sama = memperbarui.
- `delete_tool(deskripsi, nama)` menghapus alat buatan. Pembuatan dan penghapusan tercatat di log perintah.
