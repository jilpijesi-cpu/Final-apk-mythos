package id.simulasi.formktp

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import id.simulasi.formktp.data.RecordRepository
import id.simulasi.formktp.data.WilayahData
import id.simulasi.formktp.databinding.ActivityMainBinding
import id.simulasi.formktp.model.KtpRecord
import id.simulasi.formktp.ui.CardPreviewData
import id.simulasi.formktp.ui.KtpCardRenderer
import id.simulasi.formktp.ui.SavedRecordsAdapter
import id.simulasi.formktp.util.ExportHelper
import id.simulasi.formktp.util.NikParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var repo: RecordRepository
    private lateinit var savedAdapter: SavedRecordsAdapter

    private var savedRecords = mutableListOf<KtpRecord>()
    private var currentStep = 1
    private var editingId: String? = null
    private var parsedTanggalLahirIso = ""
    private var parsedJenisKelamin = ""
    private var tanggalTerbitIso = ""
    private var darkTextTheme = false
    private var selectedPhotoBitmap: Bitmap? = null
    private var selectedBackgroundBitmap: Bitmap? = null
    private var selectedSignatureBitmap: Bitmap? = null
    private var lastRenderedCard: Bitmap? = null
    private var isProgrammaticSelection = false
    private var pendingDownloadAction: (() -> Unit)? = null

    private val provinceEntries = WilayahData.provinces.entries.toList()
    private var currentCityEntries: List<Pair<String, String>> = emptyList()
    private var currentDistrictEntries: List<Pair<String, String>> = emptyList()
    private var currentVillages: List<String> = emptyList()

    private val golDarahOptions = listOf("-", "A", "B", "AB", "O")
    private val agamaOptions = listOf("", "ISLAM", "KRISTEN", "KATOLIK", "HINDU", "BUDDHA", "KONGHUCU", "KEPERCAYAAN")
    private val statusOptions = listOf("", "BELUM KAWIN", "KAWIN", "CERAI HIDUP", "CERAI MATI")
    private val wargaOptions = listOf("WNI", "WNA")

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImageFromUri(uri, isSignature = false)
    }

    private val pickSignatureLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImageFromUri(uri, isSignature = true)
    }

    private val pickBackgroundLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadBackgroundFromUri(uri)
    }

    private val legacyStoragePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingDownloadAction?.invoke()
        } else {
            showMessage("Izin penyimpanan ditolak. Gunakan tombol Bagikan untuk menyimpan file.", true)
        }
        pendingDownloadAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        repo = RecordRepository(this)
        savedRecords = repo.loadAll()

        try {
            selectedBackgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.bg_ktp_default)
            if (selectedBackgroundBitmap != null) {
                darkTextTheme = true
                b.btnToggleCardTheme.text = "Teks Gelap"
            }
        } catch (_: Exception) {}

        setupSpinners()
        setupSavedList()
        setupEvents()
        setDefaultTanggalTerbit()
        updateStepUi(1)
        refreshSavedList()
        updateLivePreview()
    }

    private fun setupSpinners() {
        val provLabels = mutableListOf("Pilih Provinsi (38 Provinsi)")
        provinceEntries.forEach { (code, name) -> provLabels.add("$code — $name") }
        setSpinnerItems(b.spProvinsi, provLabels)
        setSpinnerItems(b.spKabupaten, listOf("Pilih Kabupaten/Kota"))
        setSpinnerItems(b.spKecamatan, listOf("Pilih Kecamatan"))
        setSpinnerItems(b.spKelurahan, listOf("Pilih Kelurahan/Desa"))
        b.spKabupaten.isEnabled = false
        b.spKecamatan.isEnabled = false
        b.spKelurahan.isEnabled = false

        setSpinnerItems(b.spGolDarah, listOf("Tidak Tahu (-)", "A", "B", "AB", "O"))
        setSpinnerItems(b.spAgama, listOf("Pilih Agama", "Islam", "Kristen", "Katolik", "Hindu", "Buddha", "Konghucu", "Kepercayaan Terhadap Tuhan YME"))
        setSpinnerItems(b.spStatus, listOf("Pilih Status", "Belum Kawin", "Kawin", "Cerai Hidup", "Cerai Mati"))
        setSpinnerItems(b.spWarga, wargaOptions)
    }

    private fun setSpinnerItems(spinner: Spinner, items: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun setupSavedList() {
        savedAdapter = SavedRecordsAdapter(
            onEdit = { rec -> loadRecordIntoForm(rec) },
            onDelete = { rec -> confirmDeleteRecord(rec) }
        )
        b.rvSavedRecords.layoutManager = LinearLayoutManager(this)
        b.rvSavedRecords.adapter = savedAdapter
    }

    private fun setupEvents() {
        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_form -> showTab("form")
                R.id.nav_saved -> showTab("saved")
                R.id.nav_guide -> showTab("guide")
            }
            true
        }

        b.btnJumpPreview.setOnClickListener {
            b.bottomNav.selectedItemId = R.id.nav_form
            b.panelForm.post { b.panelForm.smoothScrollTo(0, b.previewCardSection.top) }
        }

        b.btnDemoPalembang.setOnClickListener { fillDemoData("palembang") }
        b.btnDemoJakarta.setOnClickListener { fillDemoData("jakarta") }
        b.btnResetAll.setOnClickListener {
            resetFullForm()
            showMessage("Formulir telah direset ke kondisi awal.")
        }
        b.btnCancelEdit.setOnClickListener {
            resetFullForm()
            showMessage("Mode sunting dibatalkan.")
        }

        b.btnStepTab1.setOnClickListener { goToStep(1) }
        b.btnStepTab2.setOnClickListener { goToStep(2) }
        b.btnStepTab3.setOnClickListener { goToStep(3) }
        b.btnStepTab4.setOnClickListener { goToStep(4) }

        b.btnNextStep2.setOnClickListener { goToStep(2) }
        b.btnBackStep1.setOnClickListener { goToStep(1) }
        b.btnNextStep3.setOnClickListener { goToStep(3) }
        b.btnBackStep2.setOnClickListener { goToStep(2) }
        b.btnNextStep4.setOnClickListener { goToStep(4) }
        b.btnBackStep3.setOnClickListener { goToStep(3) }
        b.btnSaveRecord.setOnClickListener { handleSaveRecord() }

        b.etNik.addTextChangedListener(simpleWatcher { text ->
            b.tvNikCounter.text = "${text.length} / 16 digit"
            if (text.length == 16) {
                applyParsedNik(text)
            } else {
                parsedTanggalLahirIso = ""
                parsedJenisKelamin = ""
                b.tvNikParsedInfo.visibility = View.GONE
            }
            updateLivePreview()
        })

        val previewWatcher = simpleWatcher { updateLivePreview() }
        listOf(b.etKabManual, b.etKecManual, b.etKelManual, b.etNama, b.etTempatLahir, b.etAlamat, b.etRt, b.etRw, b.etPekerjaan)
            .forEach { it.addTextChangedListener(previewWatcher) }

        b.spProvinsi.onItemSelectedListener = simpleItemSelected { pos ->
            if (!isProgrammaticSelection) onProvinceSelected(pos)
        }
        b.spKabupaten.onItemSelectedListener = simpleItemSelected { pos ->
            if (!isProgrammaticSelection) onCitySelected(pos)
        }
        b.spKecamatan.onItemSelectedListener = simpleItemSelected { pos ->
            if (!isProgrammaticSelection) onDistrictSelected(pos)
        }
        b.spKelurahan.onItemSelectedListener = simpleItemSelected { pos ->
            if (!isProgrammaticSelection) onVillageSelected(pos)
        }

        val simplePreviewSelect = simpleItemSelected { updateLivePreview() }
        b.spGolDarah.onItemSelectedListener = simplePreviewSelect
        b.spAgama.onItemSelectedListener = simplePreviewSelect
        b.spStatus.onItemSelectedListener = simplePreviewSelect
        b.spWarga.onItemSelectedListener = simplePreviewSelect

        b.btnPickTanggalTerbit.setOnClickListener { openDatePicker() }
        b.btnPickPhoto.setOnClickListener { pickPhotoLauncher.launch("image/*") }
        b.btnPickPhotoQuick.setOnClickListener { pickPhotoLauncher.launch("image/*") }
        b.btnClearPhoto.setOnClickListener {
            selectedPhotoBitmap = null
            updateLivePreview()
            showMessage("Pas foto dihapus.")
        }

        b.signaturePad.onSignatureChanged = { bmp ->
            selectedSignatureBitmap = bmp
            updateLivePreview()
        }
        b.btnUploadSignature.setOnClickListener { pickSignatureLauncher.launch("image/*") }
        b.btnClearSignature.setOnClickListener {
            b.signaturePad.clear()
            selectedSignatureBitmap = null
            updateLivePreview()
        }

        b.btnToggleCardTheme.text = "Teks Terang"
        b.btnToggleCardTheme.setOnClickListener {
            darkTextTheme = !darkTextTheme
            b.btnToggleCardTheme.text = if (darkTextTheme) "Teks Gelap" else "Teks Terang"
            updateLivePreview()
        }

        b.btnPickBackground.setOnClickListener { pickBackgroundLauncher.launch("image/*") }
        b.btnResetBackground.setOnClickListener {
            selectedBackgroundBitmap = null
            darkTextTheme = false
            b.btnToggleCardTheme.text = "Teks Terang"
            b.btnResetBackground.visibility = View.GONE
            updateLivePreview()
            showMessage("Kembali ke latar kartu bawaan.")
        }

        b.btnDownloadPng.setOnClickListener {
            val bmp = lastRenderedCard ?: return@setOnClickListener
            val nik = b.etNik.text?.toString()?.trim().orEmpty()
            val fileName = "Simulasi_KTP_${nik.ifBlank { "Draf" }}.png"
            runWithDownloadPermission {
                ExportHelper.savePngToDownloads(this, bmp, fileName)
                    .onSuccess { path -> showMessage("Kartu PNG tersimpan di $path") }
                    .onFailure { err -> showMessage("Gagal menyimpan PNG: ${err.message}", true) }
            }
        }

        b.btnSharePng.setOnClickListener {
            val bmp = lastRenderedCard ?: return@setOnClickListener
            val nik = b.etNik.text?.toString()?.trim().orEmpty()
            ExportHelper.shareBitmapPng(this, bmp, "Simulasi_KTP_${nik.ifBlank { "Draf" }}.png")
        }

        b.etSearchSaved.addTextChangedListener(simpleWatcher { refreshSavedList() })

        b.btnExportJson.setOnClickListener {
            if (savedRecords.isEmpty()) {
                showMessage("Belum ada data tersimpan untuk diekspor.", true)
                return@setOnClickListener
            }
            runWithDownloadPermission {
                ExportHelper.saveRecordsJsonToDownloads(this, savedRecords)
                    .onSuccess { path -> showMessage("JSON tersimpan di $path") }
                    .onFailure { err -> showMessage("Gagal ekspor JSON: ${err.message}", true) }
            }
        }

        b.btnExportCsv.setOnClickListener {
            if (savedRecords.isEmpty()) {
                showMessage("Belum ada data tersimpan untuk diekspor.", true)
                return@setOnClickListener
            }
            runWithDownloadPermission {
                ExportHelper.saveRecordsCsvToDownloads(this, savedRecords)
                    .onSuccess { path -> showMessage("CSV tersimpan di $path") }
                    .onFailure { err -> showMessage("Gagal ekspor CSV: ${err.message}", true) }
            }
        }

        b.btnClearAllSaved.setOnClickListener {
            if (savedRecords.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("Hapus Semua Data?")
                .setMessage("Seluruh ${savedRecords.size} data tersimpan beserta aset foto/tanda tangan akan dihapus permanen dari perangkat.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus Semua") { _, _ ->
                    repo.clearAll()
                    savedRecords.clear()
                    if (editingId != null) resetFullForm()
                    refreshSavedList()
                    showMessage("Semua data lokal telah dihapus.")
                }
                .show()
        }
    }

    private fun runWithDownloadPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            action()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingDownloadAction = action
            legacyStoragePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun loadBackgroundFromUri(uri: Uri) {
        try {
            val sizeBytes = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (sizeBytes > 5 * 1024 * 1024) {
                showMessage("Ukuran gambar latar maksimal 5 MB.", true)
                return
            }
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                showMessage("Format gambar latar tidak didukung.", true)
                return
            }
            selectedBackgroundBitmap = bitmap
            darkTextTheme = true
            b.btnToggleCardTheme.text = "Teks Gelap"
            b.btnResetBackground.visibility = View.VISIBLE
            updateLivePreview()
            showMessage("Gambar latar dari galeri diterapkan.")
        } catch (e: Exception) {
            showMessage("Gagal membaca gambar latar: ${e.message}", true)
        }
    }

    private fun loadImageFromUri(uri: Uri, isSignature: Boolean) {
        try {
            val sizeBytes = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (sizeBytes > 2 * 1024 * 1024) {
                showMessage("Ukuran gambar maksimal 2 MB.", true)
                return
            }
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                showMessage("Format gambar tidak didukung atau berkas rusak.", true)
                return
            }
            if (isSignature) {
                selectedSignatureBitmap = bitmap
                b.signaturePad.setSignatureBitmap(bitmap)
                showMessage("Tanda tangan berhasil dimuat.")
            } else {
                selectedPhotoBitmap = bitmap
                showMessage("Pas foto berhasil dimuat.")
            }
            updateLivePreview()
        } catch (e: Exception) {
            showMessage("Gagal membaca gambar: ${e.message}", true)
        }
    }

    private fun showTab(tab: String) {
        b.panelForm.visibility = if (tab == "form") View.VISIBLE else View.GONE
        b.panelSaved.visibility = if (tab == "saved") View.VISIBLE else View.GONE
        b.panelGuide.visibility = if (tab == "guide") View.VISIBLE else View.GONE
        if (tab == "saved") refreshSavedList()
    }

    private fun applyParsedNik(nik: String) {
        val result = NikParser.parse(nik)
        if (!result.valid) {
            parsedTanggalLahirIso = ""
            parsedJenisKelamin = ""
            b.tvNikParsedInfo.visibility = View.GONE
            showMessage(result.error ?: "NIK tidak valid.", true)
            return
        }
        parsedTanggalLahirIso = result.dateIso
        parsedJenisKelamin = result.gender

        val infoText = buildString {
            appendLine("NIK VALID • ${result.gender} • ${NikParser.formatIndoDate(result.dateIso)}")
            append("Provinsi: ${result.provName ?: result.kodeProv}")
            append(" | Kab/Kota: ${result.cityName ?: "Manual"}")
            append(" | Kec: ${result.kecName ?: "Manual"}")
        }
        b.tvNikParsedInfo.text = infoText
        b.tvNikParsedInfo.visibility = View.VISIBLE

        if (!isProgrammaticSelection && result.provName != null) {
            selectWilayahProgrammatically(
                provName = result.provName,
                cityName = result.cityName,
                districtName = result.kecName,
                villageName = null
            )
        }
    }

    private fun onProvinceSelected(pos: Int) {
        b.tilKabManual.visibility = View.GONE
        b.tilKecManual.visibility = View.GONE
        b.tilKelManual.visibility = View.GONE
        b.etKabManual.setText("")
        b.etKecManual.setText("")
        b.etKelManual.setText("")

        if (pos <= 0) {
            currentCityEntries = emptyList()
            setSpinnerItems(b.spKabupaten, listOf("Pilih Kabupaten/Kota"))
            setSpinnerItems(b.spKecamatan, listOf("Pilih Kecamatan"))
            setSpinnerItems(b.spKelurahan, listOf("Pilih Kelurahan/Desa"))
            b.spKabupaten.isEnabled = false
            b.spKecamatan.isEnabled = false
            b.spKelurahan.isEnabled = false
            updateLivePreview()
            return
        }

        val provCode = provinceEntries[pos - 1].key
        currentCityEntries = WilayahData.citiesForProvince(provCode)
        val cityLabels = mutableListOf("Pilih Kabupaten/Kota")
        currentCityEntries.forEach { cityLabels.add(it.second) }
        cityLabels.add(WilayahData.MANUAL_OPTION)
        setSpinnerItems(b.spKabupaten, cityLabels)
        b.spKabupaten.isEnabled = true

        setSpinnerItems(b.spKecamatan, listOf("Pilih Kecamatan"))
        setSpinnerItems(b.spKelurahan, listOf("Pilih Kelurahan/Desa"))
        b.spKecamatan.isEnabled = false
        b.spKelurahan.isEnabled = false
        updateLivePreview()
    }

    private fun onCitySelected(pos: Int) {
        val selectedLabel = b.spKabupaten.selectedItem?.toString().orEmpty()
        val isManual = selectedLabel == WilayahData.MANUAL_OPTION
        b.tilKabManual.visibility = if (isManual) View.VISIBLE else View.GONE
        if (!isManual) b.etKabManual.setText("")

        b.tilKecManual.visibility = View.GONE
        b.tilKelManual.visibility = View.GONE
        b.etKecManual.setText("")
        b.etKelManual.setText("")

        if (pos <= 0) {
            currentDistrictEntries = emptyList()
            setSpinnerItems(b.spKecamatan, listOf("Pilih Kecamatan"))
            setSpinnerItems(b.spKelurahan, listOf("Pilih Kelurahan/Desa"))
            b.spKecamatan.isEnabled = false
            b.spKelurahan.isEnabled = false
            updateLivePreview()
            return
        }

        currentDistrictEntries = if (!isManual && pos - 1 < currentCityEntries.size) {
            WilayahData.districtsForCity(currentCityEntries[pos - 1].first)
        } else {
            emptyList()
        }
        val kecLabels = mutableListOf("Pilih Kecamatan")
        currentDistrictEntries.forEach { kecLabels.add(it.second) }
        kecLabels.add(WilayahData.MANUAL_OPTION)
        setSpinnerItems(b.spKecamatan, kecLabels)
        b.spKecamatan.isEnabled = true

        if (isManual || currentDistrictEntries.isEmpty()) {
            b.spKecamatan.setSelection(kecLabels.lastIndex)
        }
        updateLivePreview()
    }

    private fun onDistrictSelected(pos: Int) {
        val selectedLabel = b.spKecamatan.selectedItem?.toString().orEmpty()
        val isManual = selectedLabel == WilayahData.MANUAL_OPTION
        b.tilKecManual.visibility = if (isManual) View.VISIBLE else View.GONE
        if (!isManual) b.etKecManual.setText("")

        b.tilKelManual.visibility = View.GONE
        b.etKelManual.setText("")

        if (pos <= 0) {
            currentVillages = emptyList()
            setSpinnerItems(b.spKelurahan, listOf("Pilih Kelurahan/Desa"))
            b.spKelurahan.isEnabled = false
            updateLivePreview()
            return
        }

        currentVillages = if (!isManual && pos - 1 < currentDistrictEntries.size) {
            WilayahData.villagesForDistrict(currentDistrictEntries[pos - 1].first)
        } else {
            emptyList()
        }
        val kelLabels = mutableListOf("Pilih Kelurahan/Desa")
        kelLabels.addAll(currentVillages)
        kelLabels.add(WilayahData.MANUAL_OPTION)
        setSpinnerItems(b.spKelurahan, kelLabels)
        b.spKelurahan.isEnabled = true

        if (isManual || currentVillages.isEmpty()) {
            b.spKelurahan.setSelection(kelLabels.lastIndex)
        }
        updateLivePreview()
    }

    private fun onVillageSelected(pos: Int) {
        val selectedLabel = b.spKelurahan.selectedItem?.toString().orEmpty()
        val isManual = pos > 0 && selectedLabel == WilayahData.MANUAL_OPTION
        b.tilKelManual.visibility = if (isManual) View.VISIBLE else View.GONE
        if (!isManual) b.etKelManual.setText("")
        updateLivePreview()
    }

    private fun selectWilayahProgrammatically(
        provName: String,
        cityName: String?,
        districtName: String?,
        villageName: String?
    ) {
        isProgrammaticSelection = true
        try {
            val provIdx = provinceEntries.indexOfFirst { it.value.equals(provName, ignoreCase = true) }
            if (provIdx >= 0) {
                b.spProvinsi.setSelection(provIdx + 1)
                onProvinceSelected(provIdx + 1)

                if (!cityName.isNullOrBlank()) {
                    val cityIdx = currentCityEntries.indexOfFirst { it.second.equals(cityName, ignoreCase = true) }
                    if (cityIdx >= 0) {
                        b.spKabupaten.setSelection(cityIdx + 1)
                        onCitySelected(cityIdx + 1)
                    } else {
                        val manualIdx = b.spKabupaten.count - 1
                        b.spKabupaten.setSelection(manualIdx)
                        onCitySelected(manualIdx)
                        b.etKabManual.setText(cityName)
                    }
                }

                if (!districtName.isNullOrBlank()) {
                    val kecIdx = currentDistrictEntries.indexOfFirst { it.second.equals(districtName, ignoreCase = true) }
                    if (kecIdx >= 0) {
                        b.spKecamatan.setSelection(kecIdx + 1)
                        onDistrictSelected(kecIdx + 1)
                    } else {
                        val manualIdx = b.spKecamatan.count - 1
                        b.spKecamatan.setSelection(manualIdx)
                        onDistrictSelected(manualIdx)
                        b.etKecManual.setText(districtName)
                    }
                }

                if (!villageName.isNullOrBlank()) {
                    val kelIdx = currentVillages.indexOfFirst { it.equals(villageName, ignoreCase = true) }
                    if (kelIdx >= 0) {
                        b.spKelurahan.setSelection(kelIdx + 1)
                        onVillageSelected(kelIdx + 1)
                    } else {
                        val manualIdx = b.spKelurahan.count - 1
                        b.spKelurahan.setSelection(manualIdx)
                        onVillageSelected(manualIdx)
                        b.etKelManual.setText(villageName)
                    }
                }
            }
        } finally {
            isProgrammaticSelection = false
        }
    }

    private fun getResolvedWilayah(): List<String> {
        val provPos = b.spProvinsi.selectedItemPosition
        val prov = if (provPos > 0) provinceEntries[provPos - 1].value else ""

        val kabSel = b.spKabupaten.selectedItem?.toString().orEmpty()
        val kab = when {
            b.spKabupaten.selectedItemPosition <= 0 -> ""
            kabSel == WilayahData.MANUAL_OPTION -> b.etKabManual.text?.toString()?.trim().orEmpty()
            else -> kabSel
        }

        val kecSel = b.spKecamatan.selectedItem?.toString().orEmpty()
        val kec = when {
            b.spKecamatan.selectedItemPosition <= 0 -> ""
            kecSel == WilayahData.MANUAL_OPTION -> b.etKecManual.text?.toString()?.trim().orEmpty()
            else -> kecSel
        }

        val kelSel = b.spKelurahan.selectedItem?.toString().orEmpty()
        val kel = when {
            b.spKelurahan.selectedItemPosition <= 0 -> ""
            kelSel == WilayahData.MANUAL_OPTION -> b.etKelManual.text?.toString()?.trim().orEmpty()
            else -> kelSel
        }

        return listOf(prov, kab, kec, kel)
    }

    private fun goToStep(target: Int) {
        if (target == currentStep) return
        if (target > currentStep) {
            for (s in currentStep until target) {
                if (!validateStep(s)) return
            }
        }
        updateStepUi(target)
    }

    private fun updateStepUi(step: Int) {
        currentStep = step
        b.step1Container.visibility = if (step == 1) View.VISIBLE else View.GONE
        b.step2Container.visibility = if (step == 2) View.VISIBLE else View.GONE
        b.step3Container.visibility = if (step == 3) View.VISIBLE else View.GONE
        b.step4Container.visibility = if (step == 4) View.VISIBLE else View.GONE

        val tabs = listOf(b.btnStepTab1, b.btnStepTab2, b.btnStepTab3, b.btnStepTab4)
        tabs.forEachIndexed { index, btn ->
            btn.alpha = if (index + 1 == step) 1.0f else 0.65f
        }
    }

    private fun validateStep(step: Int): Boolean {
        when (step) {
            1 -> {
                val nik = b.etNik.text?.toString()?.trim().orEmpty()
                val parsed = NikParser.parse(nik)
                if (!parsed.valid) {
                    showMessage(parsed.error ?: "NIK tidak valid.", true)
                    return false
                }
                val (prov, kab, kec, kel) = getResolvedWilayah()
                if (prov.isBlank()) { showMessage("Pilih Provinsi terlebih dahulu.", true); return false }
                if (kab.isBlank()) { showMessage("Pilih atau ketik Kabupaten/Kota.", true); return false }
                if (kec.isBlank()) { showMessage("Pilih atau ketik Kecamatan.", true); return false }
                if (kel.isBlank()) { showMessage("Pilih atau ketik Kelurahan/Desa.", true); return false }
            }
            2 -> {
                if (b.etNama.text?.toString()?.trim().isNullOrEmpty()) {
                    showMessage("Nama Lengkap wajib diisi.", true)
                    return false
                }
                if (b.etTempatLahir.text?.toString()?.trim().isNullOrEmpty()) {
                    showMessage("Tempat Lahir wajib diisi.", true)
                    return false
                }
            }
            3 -> {
                if (b.etAlamat.text?.toString()?.trim().isNullOrEmpty()) {
                    showMessage("Alamat domisili wajib diisi.", true)
                    return false
                }
            }
        }
        return true
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance()
        val parts = tanggalTerbitIso.split("-")
        if (parts.size == 3) {
            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
        }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                tanggalTerbitIso = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                b.btnPickTanggalTerbit.text = "Tanggal Pembuatan: ${NikParser.formatDdMmYyyy(tanggalTerbitIso)}"
                updateLivePreview()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun setDefaultTanggalTerbit() {
        tanggalTerbitIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        b.btnPickTanggalTerbit.text = "Tanggal Pembuatan: ${NikParser.formatDdMmYyyy(tanggalTerbitIso)}"
    }

    private fun updateLivePreview() {
        val (prov, kab, kec, kel) = getResolvedWilayah()
        val nik = b.etNik.text?.toString()?.trim().orEmpty()
        val tempat = b.etTempatLahir.text?.toString()?.trim().orEmpty()
        val rt = b.etRt.text?.toString()?.trim().orEmpty().padStart(3, '0')
        val rw = b.etRw.text?.toString()?.trim().orEmpty().padStart(3, '0')
        val agamaVal = agamaOptions.getOrElse(b.spAgama.selectedItemPosition) { "" }
        val statusVal = statusOptions.getOrElse(b.spStatus.selectedItemPosition) { "" }
        val golVal = golDarahOptions.getOrElse(b.spGolDarah.selectedItemPosition) { "-" }
        val wargaVal = wargaOptions.getOrElse(b.spWarga.selectedItemPosition) { "WNI" }

        val previewData = CardPreviewData(
            provinsiLabel = if (prov.isNotBlank()) "PROVINSI $prov" else "PROVINSI -",
            kabupatenLabel = kab.ifBlank { "KABUPATEN / KOTA -" },
            nik = nik.ifBlank { "----------------" },
            nama = b.etNama.text?.toString()?.trim().orEmpty().ifBlank { "-" },
            ttl = if (tempat.isNotBlank() || parsedTanggalLahirIso.isNotBlank()) {
                "${tempat.ifBlank { "-" }}, ${NikParser.formatIndoDate(parsedTanggalLahirIso)}"
            } else "-",
            jenisKelamin = parsedJenisKelamin.ifBlank { "-" },
            golDarah = golVal,
            alamat = b.etAlamat.text?.toString()?.trim().orEmpty().ifBlank { "-" },
            rtRw = "$rt/$rw",
            kelurahan = kel.ifBlank { "-" },
            kecamatan = kec.ifBlank { "-" },
            agama = agamaVal.ifBlank { "-" },
            statusPerkawinan = statusVal.ifBlank { "-" },
            pekerjaan = b.etPekerjaan.text?.toString()?.trim().orEmpty().ifBlank { "-" },
            kewarganegaraan = wargaVal,
            kotaTerbit = kab.ifBlank { "KOTA / KABUPATEN" },
            tanggalTerbit = NikParser.formatDdMmYyyy(tanggalTerbitIso),
            photoBitmap = selectedPhotoBitmap,
            signatureBitmap = selectedSignatureBitmap,
            customBackgroundBitmap = selectedBackgroundBitmap,
            darkTextTheme = darkTextTheme
        )

        val rendered = KtpCardRenderer.render(this, previewData)
        lastRenderedCard = rendered
        b.ivCardPreview.setImageBitmap(rendered)
    }

    private fun handleSaveRecord() {
        if (!validateStep(1) || !validateStep(2) || !validateStep(3)) return

        val (prov, kab, kec, kel) = getResolvedWilayah()
        val id = editingId ?: "ktp_${System.currentTimeMillis()}"
        val photoFile = selectedPhotoBitmap?.let { repo.saveBitmapAsset("photo", id, it, isPng = false) } ?: ""
        val sigFile = selectedSignatureBitmap?.let { repo.saveBitmapAsset("sig", id, it, isPng = true) } ?: ""

        val rt = b.etRt.text?.toString()?.trim().orEmpty().let { if (it.isNotEmpty()) it.padStart(3, '0') else "000" }
        val rw = b.etRw.text?.toString()?.trim().orEmpty().let { if (it.isNotEmpty()) it.padStart(3, '0') else "000" }

        val record = KtpRecord(
            id = id,
            nik = b.etNik.text?.toString()?.trim().orEmpty(),
            provinsi = prov,
            kabupaten = kab,
            kecamatan = kec,
            kelurahan = kel,
            tanggalLahir = parsedTanggalLahirIso,
            jenisKelamin = parsedJenisKelamin,
            nama = b.etNama.text?.toString()?.trim().orEmpty().uppercase(),
            tempatLahir = b.etTempatLahir.text?.toString()?.trim().orEmpty().uppercase(),
            golDarah = golDarahOptions.getOrElse(b.spGolDarah.selectedItemPosition) { "-" },
            alamat = b.etAlamat.text?.toString()?.trim().orEmpty().uppercase(),
            rt = rt,
            rw = rw,
            agama = agamaOptions.getOrElse(b.spAgama.selectedItemPosition) { "" }.ifBlank { "-" },
            statusPerkawinan = statusOptions.getOrElse(b.spStatus.selectedItemPosition) { "" }.ifBlank { "-" },
            pekerjaan = b.etPekerjaan.text?.toString()?.trim().orEmpty().uppercase().ifBlank { "-" },
            kewarganegaraan = wargaOptions.getOrElse(b.spWarga.selectedItemPosition) { "WNI" },
            kotaTerbit = kab.ifBlank { "KOTA / KABUPATEN" },
            tanggalTerbitIso = tanggalTerbitIso,
            photoFileName = photoFile,
            signatureFileName = sigFile,
            savedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        )

        val idx = savedRecords.indexOfFirst { it.id == id }
        if (idx >= 0) savedRecords[idx] = record else savedRecords.add(0, record)
        repo.saveAll(savedRecords)

        val actionWord = if (idx >= 0) "diperbarui" else "disimpan"
        resetFullForm()
        refreshSavedList()
        showMessage("Data ${record.nama} berhasil $actionWord ke penyimpanan privat perangkat.")
    }

    private fun loadRecordIntoForm(rec: KtpRecord) {
        resetFullForm()
        editingId = rec.id
        b.editModeBanner.visibility = View.VISIBLE
        b.btnSaveRecord.text = "Perbarui Data"

        isProgrammaticSelection = true
        b.etNik.setText(rec.nik)
        isProgrammaticSelection = false
        applyParsedNik(rec.nik)

        selectWilayahProgrammatically(rec.provinsi, rec.kabupaten, rec.kecamatan, rec.kelurahan)

        b.etNama.setText(rec.nama)
        b.etTempatLahir.setText(rec.tempatLahir)
        b.spGolDarah.setSelection(golDarahOptions.indexOf(rec.golDarah).coerceAtLeast(0))
        b.etAlamat.setText(rec.alamat)
        b.etRt.setText(rec.rt)
        b.etRw.setText(rec.rw)
        b.spAgama.setSelection(agamaOptions.indexOf(if (rec.agama == "-") "" else rec.agama).coerceAtLeast(0))
        b.spStatus.setSelection(statusOptions.indexOf(if (rec.statusPerkawinan == "-") "" else rec.statusPerkawinan).coerceAtLeast(0))
        b.etPekerjaan.setText(if (rec.pekerjaan == "-") "" else rec.pekerjaan)
        b.spWarga.setSelection(wargaOptions.indexOf(rec.kewarganegaraan).coerceAtLeast(0))

        // Pulihkan tanggal terbit, pas foto, dan tanda tangan secara utuh (perbaikan bug versi WebView)
        if (rec.tanggalTerbitIso.isNotBlank()) {
            tanggalTerbitIso = rec.tanggalTerbitIso
            b.btnPickTanggalTerbit.text = "Tanggal Pembuatan: ${NikParser.formatDdMmYyyy(tanggalTerbitIso)}"
        }
        selectedPhotoBitmap = repo.loadBitmapAsset(rec.photoFileName)
        selectedSignatureBitmap = repo.loadBitmapAsset(rec.signatureFileName)
        b.signaturePad.setSignatureBitmap(selectedSignatureBitmap)

        updateLivePreview()
        b.bottomNav.selectedItemId = R.id.nav_form
        updateStepUi(1)
        showMessage("Data ${rec.nama} dimuat ke mode sunting.")
    }

    private fun confirmDeleteRecord(rec: KtpRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus Data Tersimpan?")
            .setMessage("Hapus data ${rec.nama} (NIK ${rec.nik}) dari perangkat?")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Ya, Hapus") { _, _ ->
                repo.deleteBitmapAsset(rec.photoFileName)
                repo.deleteBitmapAsset(rec.signatureFileName)
                savedRecords.removeAll { it.id == rec.id }
                repo.saveAll(savedRecords)
                if (editingId == rec.id) resetFullForm()
                refreshSavedList()
                showMessage("Data ${rec.nama} berhasil dihapus.")
            }
            .show()
    }

    private fun refreshSavedList() {
        val q = b.etSearchSaved.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) savedRecords else savedRecords.filter {
            it.nik.contains(q) ||
                it.nama.lowercase().contains(q) ||
                it.provinsi.lowercase().contains(q) ||
                it.kabupaten.lowercase().contains(q)
        }
        savedAdapter.submitList(filtered)
        b.tvSavedEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun fillDemoData(preset: String) {
        resetFullForm()
        if (preset == "palembang") {
            b.etNik.setText("1671065508980001")
            selectWilayahProgrammatically("Sumatera Selatan", "Kota Palembang", "Ilir Timur I", "Sungai Pangeran")
            b.etNama.setText("SITI RAHMAWATI")
            b.etTempatLahir.setText("PALEMBANG")
            b.spGolDarah.setSelection(4) // O
            b.etAlamat.setText("JL. JEND. SUDIRMAN NO. 45")
            b.etRt.setText("004")
            b.etRw.setText("002")
            b.spAgama.setSelection(1) // ISLAM
            b.spStatus.setSelection(1) // BELUM KAWIN
            b.etPekerjaan.setText("KARYAWAN SWASTA")
        } else {
            b.etNik.setText("3174011708950002")
            selectWilayahProgrammatically("DKI Jakarta", "Kota Jakarta Selatan", "Tebet", "Tebet Barat")
            b.etNama.setText("BUDI SANTOSO")
            b.etTempatLahir.setText("JAKARTA")
            b.spGolDarah.setSelection(1) // A
            b.etAlamat.setText("JL. TEBET BARAT DALAM NO. 18")
            b.etRt.setText("002")
            b.etRw.setText("005")
            b.spAgama.setSelection(1) // ISLAM
            b.spStatus.setSelection(2) // KAWIN
            b.etPekerjaan.setText("WIRASWASTA")
        }
        updateLivePreview()
        showMessage("Data contoh berhasil diisikan. Ketuk 'Kartu' untuk melihat pratinjau.")
    }

    private fun resetFullForm() {
        editingId = null
        parsedTanggalLahirIso = ""
        parsedJenisKelamin = ""
        selectedPhotoBitmap = null
        selectedSignatureBitmap = null

        b.editModeBanner.visibility = View.GONE
        b.btnSaveRecord.text = "Simpan Data"
        b.tvNikParsedInfo.visibility = View.GONE

        isProgrammaticSelection = true
        b.etNik.setText("")
        b.spProvinsi.setSelection(0)
        onProvinceSelected(0)
        b.etNama.setText("")
        b.etTempatLahir.setText("")
        b.spGolDarah.setSelection(0)
        b.etAlamat.setText("")
        b.etRt.setText("")
        b.etRw.setText("")
        b.spAgama.setSelection(0)
        b.spStatus.setSelection(0)
        b.etPekerjaan.setText("")
        b.spWarga.setSelection(0)
        isProgrammaticSelection = false

        b.signaturePad.clear()
        setDefaultTanggalTerbit()
        updateStepUi(1)
        updateLivePreview()
    }

    private fun showMessage(msg: String, isError: Boolean = false) {
        val sb = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT)
        if (isError) {
            sb.setBackgroundTint(ContextCompat.getColor(this, R.color.danger))
            sb.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        }
        sb.show()
    }

    private fun simpleWatcher(onChanged: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged(s?.toString().orEmpty())
    }

    private fun simpleItemSelected(onSelected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
}
