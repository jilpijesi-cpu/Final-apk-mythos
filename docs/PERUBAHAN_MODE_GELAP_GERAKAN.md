# Mode gelap, aksesibilitas, kurangi gerakan (belum dikompilasi)

- Palette: warna netral kini getter yang mengikuti `Palette.dark`; `tint()` bercampur ke `Palette.TINT_BASE`.
- Theme.kt: `applyTheme()` membaca uiMode sistem; `barFlags()` untuk ikon status/navigasi.
- values-night + drawable-night: tema dan dialog gelap. Activity dibangun ulang saat tema sistem berganti
  (ditunda bila agen berjalan atau Chat samaran aktif).
- (Dihapus kemudian) Motion.kt / "Kurangi gerakan": fitur ini sudah dibuang; animasi selalu berjalan.
- Lampiran: deskripsi konten kartu dan tombol hapus; area sentuh tombol hapus 44dp.
- Tes baru: ThemeMotionTest di PaletteTest.kt. Jalankan `./gradlew testDebugUnitTest` (CI sudah menjalankannya).

## Pemecahan MainActivity (langkah 1)
Blok konfigurasi penyedia, kunci API, dan prompt sistem dipindah ke ProviderConfig.kt sebagai fungsi ekstensi
(preset, readStoredKey, pvOf, migrateKeys, cfgKey, cfgModel, cfgBase, needsKey, workdir, baseSystem, compactSystem,
compactPromptOn). Isi fungsi tidak berubah; pemanggil tidak perlu diubah. MainActivity.kt: 1033 -> 936 baris.

## Pemecahan MainActivity (langkah 3)
- MainLayout.kt: `initPrefsAndMigrations`, `buildTopBar`, `buildCenter`, `buildInputPill` (dulu satu `onCreate` ±300 baris;
  urutan pemanggilan dipertahankan).
- ActivityResults.kt: `handleActivityResult`, `handlePermissionsResult`, `onUi`. Override tetap di MainActivity dan meneruskan.
- MainConstants.kt: konstanta tanpa status (skipWords, roCmds, findBad, truncMsg, allowCmd, toolCheckCmd, buildRe, regex Markdown,
  kwSet, hashLangs).
- MainActivity.kt: 1033 -> 525 baris.
- Sengaja tetap di MainActivity: properti bernilai-berubah (termasuk blok failover), `StepCard` (inner class), `newStepCard`,
  `termuxHome`/`termuxTmp` (dipakai dengan awalan objek), `retryable` (bertabrakan dengan variabel lokal di AgentLoop).
