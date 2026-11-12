# v76: alat pengaduan, pengaman, dan GitHub dari versi Gemini (Paket A + B + C + D)

Dasar tetap MythosV6-patched. Hanya alat berikut yang dipindahkan; sistem build (AGP/Kotlin/Gradle) TIDAK diubah.

## Paket A: pengaduan uang tertahan
- `CaseLedger`: catatan kasus terstruktur (tiket, nominal, linimasa klaim, ketidakkonsistenan, tenggat hari kerja), selaras dengan `KASUS.md`. Terdaftar bersama ComposeEmail/SetReminder/SetDeadlineNotice.
- `Playbooks`: tiga playbook bawaan (e-commerce/Kemendag-BPSK, transfer & dompet digital/BI, bank-paylater-pinjol-asuransi/OJK). Dipakai hanya bila proyek belum punya `playbook/*.md`; dipilih dari kata kunci tugas. Kontak resmi tetap harus diverifikasi lewat web.

## Paket B: pengaman
- `SensitiveDataGuard`: menyamarkan OTP/PIN/CVV/kartu/sandi pada konten EKSTERNAL (hasil pencarian web, halaman galat) dan menetralkan tag protokol.
- Hasil alat (isi file) hanya dinetralkan tag protokolnya, TIDAK disamarkan, agar agen melihat kode apa adanya (Edit butuh teks asli).
- `SecurityAuditLog`: catatan setiap alat yang dijalankan di luar folder proyek, dengan argumen sensitif disamarkan.
- `WebFetch` bisa mengunduh PDF publik ke `uploads/web_docs/` untuk dibaca dengan `ViewImages`.
- Pesan pertama (memori proyek dan pohon file) menetralkan tag `</system-reminder>` palsu.

## Paket D: alat GitHub tambahan
- `GithubImportRepo` (tarik file repo ke ruang kerja), `GithubCreatePR` (selalu minta persetujuan), `GithubIssues` (daftar/baca issue; isinya ditandai data tak tepercaya), `GithubDownloadArtifact` (unduh ZIP artefak build ke `artifacts/`). Semua butuh token + repo GitHub di Pengaturan.
- `BuildService` diperluas dengan empat metode berimplementasi bawaan "tidak didukung".
- `workflowDigest` kini memindai sampai kedalaman 16 dan tanpa batas 40 berkas, supaya skrip tersembunyi tetap tampil saat persetujuan workflow.
- Hasil `GithubIssues` menandai sesi sebagai terpapar konten eksternal (hanya tercatat di audit; tidak ada gerbang izin tambahan karena `GithubCreatePR` sudah selalu minta persetujuan).

## Paket C: Google Workspace (Gmail, Kalender, Drive)
- Alat agen: `GmailSearchRead` (cari dan baca email; isi email ditandai data tak tepercaya), `GmailDraftSend` (buat draf atau kirim; SELALU minta persetujuan), `GoogleCalendarSync` (tenggat SLA dan pengingat), `GoogleDriveCaseVault` (folder dan PDF bukti; hanya berkas buatan aplikasi, scope `drive.file`).
- Login OAuth 2.0 PKCE lewat peramban, tautan balik `com.mythos.app:/oauth2redirect`. Multi-akun, ganti akun aktif, sakelar Gmail/Kalender/Drive terpisah. Token disimpan terenkripsi (SecretBox); sesi PKCE juga terenkripsi.
- Layar "Akun Google" dibuat ringkas: kartu akun (ketuk untuk ganti aktif, Hapus), satu kolom Client ID OAuth **milik Anda sendiri** (kosong bawaan), satu tombol Hubungkan, tiga sakelar (Gmail, Kalender, Drive), satu tombol Uji koneksi. Tempel kode manual disembunyikan di "Tidak kembali ke aplikasi?". Pilihan scope mengikuti sakelar; input token mentah dihapus.
- Setelah agen membaca email/web, tulis ke Kalender/Drive wajib disetujui walau mode izin longgar.
- `IntegrationTester` hanya berisi uji Google; `BuildConfig` tidak dipakai (tidak ada perubahan Gradle).
- Menu: lembar konektor dan menu titik tiga di obrolan punya baris "Google Workspace".
- Batasan: `GmailDraftSend` membuat email BARU (tanpa pengait utas), jadi balasan tidak menyatu dengan utas asli.

## Tidak dibawa
Penyederhanaan Pengaturan dan preset model versi Gemini, serta perubahan sistem build (AGP/Kotlin/Gradle).

## Tes
`CaseAndGuardTest` (penjaga data, pesan pertama, nama alat longgar, CaseLedger, playbook bawaan, audit log) `GithubToolsTest` (alat GitHub, persetujuan PR, digest workflow dalam), dan `GoogleWorkspaceTest` (PKCE, alat Gmail/Kalender/Drive, multi-akun, penukaran kode, uji koneksi tanpa bocor token).
