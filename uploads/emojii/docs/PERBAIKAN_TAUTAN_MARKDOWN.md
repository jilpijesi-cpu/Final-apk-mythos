# Perbaikan: tautan Markdown tampil mentah di gelembung chat

**Gejala (dari rekaman layar, ±1:06–1:17):** jawaban audit menampilkan teks seperti
`[storage.py](/data/data/com.termux/files/home/MYTHOS/mythos_benchmark_v7r/storage.py:23)`
apa adanya, sehingga kalimat jadi panjang dan sulit dibaca.

**Penyebab:** `inlineRe` / `inline()` hanya mengenali kode inline, **tebal**, dan *miring*.
Sintaks `[teks](tujuan)` tidak pernah diproses.

**Perbaikan:**
- `MainConstants.kt`: `inlineRe` mendapat alternatif tautan (grup 4 = teks, grup 5 = tujuan).
- `Markdown.kt`: `inline()` menampilkan hanya teks tautan. Tujuan `http(s)` bisa diketuk (`URLSpan`);
  path berkas lokal cukup digarisbawahi. Teks tebal kini diproses rekursif, jadi `**[a](b)**` juga benar.
  `LinkMovementMethod` dipasang di blok teks.
- Tes baru: `MarkdownLinkTest.kt`.

Belum dikompilasi di sandbox (tanpa Gradle/jaringan); jalankan `./gradlew testDebugUnitTest`.

## Perbaikan tambahan dari rekaman yang sama
- **Label langkah mentah** ("Menjalankan cat build.gradle.kts settings.gradle.kts", ±0:38): `looksLikeRead()` di
  `CodexActivityUi.kt` kini membaca perintah setelah pembungkus `bash -lc '...'` dilepas, sehingga tampil
  "Membaca build.gradle.kts dan settings.gradle.kts". Perintah yang menulis (`>`, `sed -i`, `tee`) tidak dianggap baca.
- **Nama file palsu** ("Membaca build-apk.yml dan pathlib.Path", ±0:44): `commandSubject()` kini hanya menerima
  ekstensi file sungguhan (`FILE_EXTS`), jadi `pathlib.Path` / `os.path` tidak lagi tampil sebagai file.
- **Layar kosong ±9 detik setelah menekan Izinkan** (0:14–0:23): `ModelCall.kt` kini memulihkan status kerja
  (`saveStatus`/`restoreStatus`) sesudah dialog izin CLI. Akar masalahnya belum bisa dibuktikan tanpa menjalankan
  aplikasi; ini mitigasi berdasarkan pola yang sudah dipakai di tempat lain.

## Gaya tulisan dan efek mengetik ala Claude (video 1000241971 / 1000241972)
- **Gaya teks** (`Markdown.kt`, `styleAssistantText`): huruf serif, 16,5 sp, jarak baris 1,42. Dipakai oleh teks yang
  sedang mengalir dan hasil akhirnya, jadi tidak ada lompatan huruf/ukuran saat balasan selesai. Gelembung pengguna 15 sp.
- **Jawaban agen kini mengalir** (`ChatMessages.kt`): jalur agen dulu menampilkan jawaban lengkap sekaligus lewat
  `streamUpdate`; sekarang memakai penghalus yang sama dengan Codex.
- **Laju halus** (`StreamSmoother.Pacer`): kecepatan sasaran dikejar eksponensial (tau 220 ms), bukan melonjak.
- **Memudar per huruf**: tiap huruf memudar masuk menurut umurnya (ease-out 260 ms) menggantikan 3 tingkat alpha kaku;
  huruf terakhir selesai memudar dulu sebelum diganti hasil akhir (tidak "pop").
- **Penanda Markdown belum ditutup disembunyikan** selama mengalir (`StreamMarkup.kt`): tidak ada `**` / `` ` `` / `[x](` mentah.
- **Saran (chips) menunggu** sampai teks selesai diketik.
- **Teks DSML mentah** (`< | DSML | invoke name="LS">` di kedua video): `TextToolCalls.kt` kini memulihkan panggilan itu
  menjadi alat aplikasi (LS→`ls`/`find`, Read→`cat`, Bash→`run_command`, Write→`write_file`) sehingga agen lanjut bekerja
  alih-alih berhenti dengan teks mentah sebagai "jawaban".
