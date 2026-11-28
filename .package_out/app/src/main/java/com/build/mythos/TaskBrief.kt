package com.build.mythos

// Tahap pemahaman: sebelum agen bertindak pada pesan pengguna yang baru, model merumuskan tujuan dan
// langkah awal (PAHAM:), atau bertanya bila ada informasi penting yang kurang (TANYA:).
// Rumusan PAHAM dipakai sebagai arahan di prompt sistem untuk tugas itu. Tidak ada pemanggilan tool.

internal sealed class Brief {
    class Paham(val text: String) : Brief()
    object Kosong : Brief()
}


