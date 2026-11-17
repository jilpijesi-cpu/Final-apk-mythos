# Perbaikan alur streaming Mythos 2.20

## Perubahan
- Aktivitas Codex yang benar-benar diterima dari App Server kini menghasilkan narasi singkat berbasis jenis alat/peristiwa dan, jika ada, nama file atau kueri aktual. Tidak ada rotasi kalimat acak.
- Narasi ditampilkan karakter demi karakter, lalu indikator kerja kembali. Event status baru ditampung selama narasi agar kalimat tidak terpotong di tengah.
- Ikon tiga bola oranye tidak diubah: `ClaudeDots.kt`, warna `Live.ORANGE`, mode, ukuran, jarak, slot, dan kode animasi tetap sama. Saat narasi ditulis, slot ikon hanya dibuat transparan sementara (`alpha=0`) agar tata letak tetap stabil; ikon yang sama kembali terlihat setelahnya.
- Jawaban assistant tetap menggunakan delta asli Codex yang diterima, tanpa ticker typewriter jawaban buatan.
- Tidak ada perubahan tema, bentuk gelembung, maupun tata letak chat lain.

## Verifikasi statis
- `ClaudeDots.kt` identik dengan arsip 2.19.
- `Palette.kt` identik dengan arsip 2.19.
- `setIcon()` dan konstanta ukuran/warna ikon pada `LiveStatusLine.kt` tidak diubah; tambahan terbatas pada siklus narasi.
- Build dan tes Android harus dijalankan untuk memastikan seluruh jalur runtime di perangkat; keberhasilan tidak diasumsikan sebelum dijalankan.
