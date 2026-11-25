package id.simulasi.formktp.model

import android.graphics.Bitmap

data class KtpData(
    val nik: String = "",
    val nama: String = "",
    val tempatLahir: String = "",
    val tanggalLahir: String = "",
    val jenisKelamin: String = "",
    val golDarah: String = "-",
    val alamat: String = "",
    val rtRw: String = "",
    val kelDesa: String = "",
    val kecamatan: String = "",
    val agama: String = "",
    val statusPerkawinan: String = "",
    val pekerjaan: String = "",
    val kewarganegaraan: String = "WNI",
    val berlakuHingga: String = "SEUMUR HIDUP",
    val provinsi: String = "",
    val kabupaten: String = "",
    val kotaTerbit: String = "",
    val tanggalTerbit: String = "",
    val foto: Bitmap? = null,
    val tandaTangan: Bitmap? = null
)
