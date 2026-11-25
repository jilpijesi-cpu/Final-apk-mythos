package id.simulasi.formktp.model

import org.json.JSONObject

data class KtpRecord(
    val id: String,
    val nik: String,
    val provinsi: String,
    val kabupaten: String,
    val kecamatan: String,
    val kelurahan: String,
    val tanggalLahir: String,       // Format ISO: yyyy-MM-dd
    val jenisKelamin: String,       // LAKI-LAKI / PEREMPUAN
    val nama: String,
    val tempatLahir: String,
    val golDarah: String,
    val alamat: String,
    val rt: String,
    val rw: String,
    val agama: String,
    val statusPerkawinan: String,
    val pekerjaan: String,
    val kewarganegaraan: String,
    val kotaTerbit: String,
    val tanggalTerbitIso: String,   // Format ISO: yyyy-MM-dd
    val photoFileName: String = "",
    val signatureFileName: String = "",
    val savedAt: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("nik", nik)
        put("provinsi", provinsi)
        put("kabupaten", kabupaten)
        put("kecamatan", kecamatan)
        put("kelurahan", kelurahan)
        put("tanggalLahir", tanggalLahir)
        put("jenisKelamin", jenisKelamin)
        put("nama", nama)
        put("tempatLahir", tempatLahir)
        put("golDarah", golDarah)
        put("alamat", alamat)
        put("rt", rt)
        put("rw", rw)
        put("agama", agama)
        put("statusPerkawinan", statusPerkawinan)
        put("pekerjaan", pekerjaan)
        put("kewarganegaraan", kewarganegaraan)
        put("kotaTerbit", kotaTerbit)
        put("tanggalTerbitIso", tanggalTerbitIso)
        put("photoFileName", photoFileName)
        put("signatureFileName", signatureFileName)
        put("savedAt", savedAt)
    }

    fun toExportJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("nik", nik)
        put("nama", nama)
        put("tempatLahir", tempatLahir)
        put("tanggalLahir", tanggalLahir)
        put("jenisKelamin", jenisKelamin)
        put("golDarah", golDarah)
        put("alamat", alamat)
        put("rt", rt)
        put("rw", rw)
        put("kelurahan", kelurahan)
        put("kecamatan", kecamatan)
        put("kabupaten", kabupaten)
        put("provinsi", provinsi)
        put("agama", agama)
        put("statusPerkawinan", statusPerkawinan)
        put("pekerjaan", pekerjaan)
        put("kewarganegaraan", kewarganegaraan)
        put("kotaTerbit", kotaTerbit)
        put("tanggalTerbit", tanggalTerbitIso)
        put("savedAt", savedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): KtpRecord = KtpRecord(
            id = obj.optString("id"),
            nik = obj.optString("nik"),
            provinsi = obj.optString("provinsi"),
            kabupaten = obj.optString("kabupaten"),
            kecamatan = obj.optString("kecamatan"),
            kelurahan = obj.optString("kelurahan"),
            tanggalLahir = obj.optString("tanggalLahir"),
            jenisKelamin = obj.optString("jenisKelamin"),
            nama = obj.optString("nama"),
            tempatLahir = obj.optString("tempatLahir"),
            golDarah = obj.optString("golDarah", "-"),
            alamat = obj.optString("alamat"),
            rt = obj.optString("rt", "000"),
            rw = obj.optString("rw", "000"),
            agama = obj.optString("agama", "-"),
            statusPerkawinan = obj.optString("statusPerkawinan", "-"),
            pekerjaan = obj.optString("pekerjaan", "-"),
            kewarganegaraan = obj.optString("kewarganegaraan", "WNI"),
            kotaTerbit = obj.optString("kotaTerbit"),
            tanggalTerbitIso = obj.optString("tanggalTerbitIso"),
            photoFileName = obj.optString("photoFileName", ""),
            signatureFileName = obj.optString("signatureFileName", ""),
            savedAt = obj.optString("savedAt", "")
        )
    }
}
