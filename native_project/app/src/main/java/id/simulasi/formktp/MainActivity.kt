package id.simulasi.formktp

import android.app.DatePickerDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import id.simulasi.formktp.databinding.ActivityMainBinding
import id.simulasi.formktp.logic.KtpRenderer
import id.simulasi.formktp.logic.NikParser
import id.simulasi.formktp.model.KtpData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var photoBitmap: Bitmap? = null
    private var signatureBitmap: Bitmap? = null
    private var generatedBitmap: Bitmap? = null

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            photoBitmap = uriToBitmap(it)
            Toast.makeText(this, "Foto terpilih", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickSignatureLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            signatureBitmap = uriToBitmap(it)
            Toast.makeText(this, "Tanda tangan terpilih", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.etNik.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val nik = s.toString()
                if (nik.length == 16) {
                    val result = NikParser.parseIndonesianNIK(nik)
                    if (result.valid) {
                        binding.etTglLahir.setText(result.dateStr)
                        binding.etKelDesa.setText(result.kecName)
                        binding.etKecamatan.setText(result.kecName)
                        Toast.makeText(this@MainActivity, "Data wilayah terdeteksi: ${result.provName}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        binding.etTglLahir.setOnClickListener {
            showDatePicker { date -> binding.etTglLahir.setText(date) }
        }

        binding.btnPickPhoto.setOnClickListener { pickPhotoLauncher.launch("image/*") }
        binding.btnPickSignature.setOnClickListener { pickSignatureLauncher.launch("image/*") }

        binding.btnGenerate.setOnClickListener {
            generateKtp()
        }

        binding.btnSave.setOnClickListener {
            saveToGallery()
        }
    }

    private fun generateKtp() {
        val nik = binding.etNik.text.toString()
        if (nik.length != 16) {
            binding.tilNik.error = "NIK harus 16 digit"
            return
        }
        binding.tilNik.error = null

        val nikResult = NikParser.parseIndonesianNIK(nik)
        
        // Decode background with scaling to avoid OOM
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeResource(resources, R.drawable.default_bg, options)
        
        // Target size for KTP background is 1011x638
        options.inSampleSize = calculateInSampleSize(options, 1011, 638)
        options.inJustDecodeBounds = false
        val bgBitmap = BitmapFactory.decodeResource(resources, R.drawable.default_bg, options)

        val data = KtpData(
            nik = nik,
            nama = binding.etNama.text.toString(),
            tempatLahir = binding.etTempatLahir.text.toString(),
            tanggalLahir = binding.etTglLahir.text.toString(),
            jenisKelamin = nikResult.gender ?: "LAKI-LAKI",
            alamat = binding.etAlamat.text.toString(),
            rtRw = binding.etRtRw.text.toString(),
            kelDesa = binding.etKelDesa.text.toString(),
            kecamatan = binding.etKecamatan.text.toString(),
            provinsi = nikResult.provName ?: "PROVINSI JAWA BARAT",
            kabupaten = nikResult.cityName ?: "KOTA BANDUNG",
            kotaTerbit = nikResult.cityName ?: "KOTA BANDUNG",
            tanggalTerbit = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date()),
            foto = photoBitmap,
            tandaTangan = signatureBitmap,
            backgroundBitmap = bgBitmap
        )

        generatedBitmap = KtpRenderer.render(data)
        binding.ivPreview.setImageBitmap(generatedBitmap)
        binding.ivPreview.visibility = View.VISIBLE
        binding.btnSave.visibility = View.VISIBLE
        
        Toast.makeText(this, "KTP Berhasil Dibuat", Toast.LENGTH_SHORT).show()
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it, null, options)
            }
            
            // Scale down user photos (usually large) to max 1024px to save memory
            options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
            options.inJustDecodeBounds = false
            
            contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToGallery() {
        val bitmap = generatedBitmap ?: return
        val fileName = "KTP_${System.currentTimeMillis()}.png"
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { 
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { 
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            Toast.makeText(this, "Tersimpan di Download/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDatePicker(onDateSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            val date = String.format("%02d-%02d-%d", dayOfMonth, month + 1, year)
            onDateSelected(date)
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }
}