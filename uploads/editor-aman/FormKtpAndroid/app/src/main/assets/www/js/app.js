const state = {
    activeTab: "form",
    currentStep: 1,
    isNikValid: false,
    editingId: null,
    selectedPhotoDataUrl: "",
    signatureDataUrl: "",
    selectedPhotoName: "",
    selectedPhotoSize: "",
    savedRecords: [],
    pendingDeleteId: null,
    customTemplateImg: null,
    textTheme: "light",
    calibration: { offsetX: 0, offsetY: 0, fontScale: 1, fontWeight: 0, blurPx: 0, opacity: 1 }
};

document.addEventListener("DOMContentLoaded", () => {
    state.savedRecords = loadSavedRecords();
    populateProvinces();
    bindEvents();
    setupSignaturePad();
    setDefaultTanggalTerbit();
    updateSavedBadge();
    renderSavedList();
    updateLivePreview();
    if (window.lucide) window.lucide.createIcons();
});

function bindEvents() {
    document.querySelectorAll(".tab-trigger").forEach(btn => {
        btn.addEventListener("click", () => switchTab(btn.dataset.tab));
    });

    document.getElementById("btnTogglePreviewDrawer").addEventListener("click", () => {
        if (state.activeTab !== "form") switchTab("form");
        document.getElementById("previewCardSection").scrollIntoView({ behavior: "smooth", block: "center" });
    });

    document.getElementById("closeAlertBtn").addEventListener("click", clearNotification);

    document.getElementById("btnDemoPalembang").addEventListener("click", () => fillDemoData("palembang"));
    document.getElementById("btnDemoJakarta").addEventListener("click", () => fillDemoData("jakarta"));
    document.getElementById("btnResetAll").addEventListener("click", () => {
        resetFullForm();
        showNotification("Formulir telah direset ke kondisi awal.", "success");
    });
    document.getElementById("cancelEditBtn").addEventListener("click", () => {
        resetFullForm();
        showNotification("Mode sunting dibatalkan.", "success");
    });

    document.querySelectorAll(".step-action-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            const target = parseInt(btn.dataset.nextStep || btn.dataset.prevStep, 10);
            goToStep(target);
        });
    });
    document.querySelectorAll(".step-nav-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            const target = parseInt(btn.dataset.stepNav, 10);
            goToStep(target);
        });
    });

    document.getElementById("inputNik").addEventListener("input", handleNikInput);

    document.getElementById("selectProvinsi").addEventListener("change", () => handleProvinsiChange());
    document.getElementById("selectKabupaten").addEventListener("change", () => handleKabupatenChange());
    document.getElementById("selectKecamatan").addEventListener("change", () => handleKecamatanChange());
    document.getElementById("selectKelurahan").addEventListener("change", handleKelurahanChange);

    ["inputKabupatenManual", "inputKecamatanManual", "inputKelurahanManual"].forEach(id => {
        document.getElementById(id).addEventListener("input", (e) => {
            e.target.value = e.target.value.toUpperCase();
            updateLivePreview();
        });
    });

    ["inputNama", "inputTempatLahir", "inputAlamat", "inputPekerjaan"].forEach(id => {
        document.getElementById(id).addEventListener("input", (e) => {
            e.target.value = e.target.value.toUpperCase();
            updateLivePreview();
        });
    });

    ["inputRt", "inputRw"].forEach(id => {
        const el = document.getElementById(id);
        el.addEventListener("input", () => {
            el.value = el.value.replace(/[^0-9]/g, "");
            updateLivePreview();
        });
        el.addEventListener("blur", () => {
            if (el.value) el.value = el.value.padStart(3, "0");
            updateLivePreview();
        });
    });

    ["selectGolDarah", "selectAgama", "selectStatus", "selectWarga"].forEach(id => {
        document.getElementById(id).addEventListener("change", updateLivePreview);
    });

    const dropZone = document.getElementById("dropZone");
    const fileInput = document.getElementById("fileFoto");
    dropZone.addEventListener("click", () => fileInput.click());
    dropZone.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            fileInput.click();
        }
    });
    dropZone.addEventListener("dragover", (e) => {
        e.preventDefault();
        dropZone.classList.add("border-indigo-500", "bg-indigo-50/50");
    });
    dropZone.addEventListener("dragleave", () => {
        dropZone.classList.remove("border-indigo-500", "bg-indigo-50/50");
    });
    dropZone.addEventListener("drop", (e) => {
        e.preventDefault();
        dropZone.classList.remove("border-indigo-500", "bg-indigo-50/50");
        if (e.dataTransfer.files && e.dataTransfer.files[0]) {
            processPhotoFile(e.dataTransfer.files[0]);
        }
    });
    fileInput.addEventListener("change", (e) => {
        if (e.target.files && e.target.files[0]) processPhotoFile(e.target.files[0]);
    });
    document.getElementById("clearFotoBtn").addEventListener("click", clearFoto);

    document.getElementById("ktpForm").addEventListener("submit", handleFormSubmit);
    document.getElementById("btnDownloadPng").addEventListener("click", downloadKtpCard);
    document.getElementById("btnUploadTemplate").addEventListener("click", () => {
        document.getElementById("inputTemplateFile").click();
    });
    document.getElementById("inputTemplateFile").addEventListener("change", (e) => {
        if (e.target.files && e.target.files[0]) processTemplateFile(e.target.files[0]);
    });
    document.getElementById("btnResetTemplate").addEventListener("click", resetCustomTemplate);
    document.getElementById("btnToggleTextTheme").addEventListener("click", toggleTextTheme);
    ["calOffsetX", "calOffsetY", "calFontScale", "calFontWeight", "calBlurPx", "calOpacity"].forEach(id => {
        document.getElementById(id).addEventListener("input", handleCalibrationInput);
    });
    document.getElementById("btnResetCalibration").addEventListener("click", resetCalibration);
    document.querySelectorAll(".cal-step-btn").forEach(btn => {
        btn.addEventListener("click", () => {
            const target = document.getElementById(btn.dataset.calTarget);
            if (!target) return;
            const delta = parseInt(btn.dataset.calDelta, 10) || 0;
            const min = parseInt(target.min, 10);
            const max = parseInt(target.max, 10);
            const next = Math.max(min, Math.min(max, (parseInt(target.value, 10) || 0) + delta));
            target.value = String(next);
            handleCalibrationInput();
        });
    });

    document.getElementById("searchSavedInput").addEventListener("input", renderSavedList);
    document.getElementById("btnExportJson").addEventListener("click", exportRecordsAsJson);
    document.getElementById("btnExportCsv").addEventListener("click", exportRecordsAsCsv);

    document.getElementById("cancelDeleteBtn").addEventListener("click", closeDeleteModal);
    const closePngBtn = document.getElementById("closePngModalBtn");
    if (closePngBtn) {
        closePngBtn.addEventListener("click", () => {
            document.getElementById("pngResultModal").classList.add("hidden");
        });
    }
    const sharePngBtn = document.getElementById("btnSharePng");
    if (sharePngBtn) {
        sharePngBtn.addEventListener("click", async () => {
            const canvasEl = document.getElementById("ktpCanvas");
            const nikVal = document.getElementById("inputNik").value.trim();
            const fileName = `Simulasi_KTP_${nikVal || "Draf"}.png`;
            if (!canvasEl) return;

    if (window.AndroidBridge && typeof window.AndroidBridge.savePng === "function") {
        try {
            const dataUrl = exportCanvasToPngDataUrl(canvasEl);
            window.AndroidBridge.savePng(dataUrl, fileName);
            showNotification("Kartu PNG disimpan ke folder Download ponsel.", "success");
            return;
        } catch (err) { /* fallback ke browser */ }
    }
            canvasEl.toBlob(async (blob) => {
                if (!blob) return;
                const file = new File([blob], fileName, { type: "image/png" });
                if (navigator.share && navigator.canShare && navigator.canShare({ files: [file] })) {
                    try {
                        await navigator.share({ files: [file], title: fileName });
                        return;
                    } catch (e) { /* user cancelled or blocked */ }
                }
                showNotification("Tekan & tahan gambar di modal lalu pilih Simpan Gambar, atau buka file HTML di Chrome.", "info");
            }, "image/png");
        });
    }
    document.getElementById("confirmDeleteBtn").addEventListener("click", confirmDeleteRecord);
}

function switchTab(tabName) {
    state.activeTab = tabName;
    document.querySelectorAll(".tab-panel").forEach(p => p.classList.remove("active"));
    const targetId = tabName === "form" ? "panelForm" : tabName === "saved" ? "panelSaved" : "panelGuide";
    document.getElementById(targetId).classList.add("active");

    document.querySelectorAll(".tab-trigger").forEach(btn => {
        const isActive = btn.dataset.tab === tabName;
        btn.className = isActive
            ? "tab-trigger min-h-[52px] rounded-xl flex flex-col items-center justify-center gap-1 transition-all text-indigo-600 bg-indigo-50/80 font-bold relative"
            : "tab-trigger min-h-[52px] rounded-xl flex flex-col items-center justify-center gap-1 transition-all text-slate-500 font-medium relative";
    });

    if (tabName === "saved") renderSavedList();
    window.scrollTo({ top: 0, behavior: "smooth" });
    if (window.lucide) window.lucide.createIcons();
}

function populateProvinces() {
    const selectProv = document.getElementById("selectProvinsi");
    selectProv.innerHTML = '<option value="">Pilih Provinsi (38 Provinsi)</option>';
    Object.entries(wilayahData.provinces).forEach(([code, name]) => {
        const opt = document.createElement("option");
        opt.value = name;
        opt.textContent = `${code} — ${name}`;
        opt.dataset.code = code;
        selectProv.appendChild(opt);
    });
}

function handleNikInput(e) {
    let val = e.target.value.replace(/[^0-9]/g, "");
    if (val.length > 16) val = val.substring(0, 16);
    e.target.value = val;
    document.getElementById("nikCharCounter").textContent = `${val.length} / 16`;

    if (val.length === 16) {
        applyParsedNIK(val);
    } else {
        state.isNikValid = false;
        hideNikInfo();
        if (val.length >= 2) {
            autoSelectWilayahFromPrefix(val);
        }
    }
    updateLivePreview();
}

async function autoSelectWilayahFromPrefix(nikPrefix) {
    const kodeProv = nikPrefix.substring(0, 2);
    const kodeKota = nikPrefix.length >= 4 ? nikPrefix.substring(0, 4) : null;
    const kodeKec = nikPrefix.length >= 6 ? nikPrefix.substring(0, 6) : null;

    const provName = wilayahData.provinces[kodeProv] || null;
    if (!provName) return;

    let cityName = kodeKota ? (wilayahData.cities[kodeKota] || null) : null;
    let kecName = kodeKec ? (wilayahData.districts[kodeKec] || null) : null;

    // Fallback ke API wilayah publik jika kode Kab/Kec belum ada di daftar lokal
    if (kodeKota && !cityName) {
        try {
            const res = await fetch(`https://www.emsifa.com/api-wilayah-indonesia/api/regencies/${kodeProv}.json`);
            if (res.ok) {
                const list = await res.json();
                const found = list.find(item => item.id === kodeKota);
                if (found) {
                    cityName = found.name.replace(/\b\w/g, c => c.toUpperCase()).replace(/^Kab\.\s*/i, "Kabupaten ");
                    wilayahData.cities[kodeKota] = cityName;
                }
            }
        } catch (err) { /* abaikan jika offline */ }
    }
    if (kodeKec && cityName && !kecName) {
        try {
            const res = await fetch(`https://www.emsifa.com/api-wilayah-indonesia/api/districts/${kodeKota}.json`);
            if (res.ok) {
                const list = await res.json();
                const found = list.find(item => item.id === kodeKec);
                if (found) {
                    kecName = found.name.replace(/\b\w/g, c => c.toUpperCase());
                    wilayahData.districts[kodeKec] = kecName;
                }
            }
        } catch (err) { /* abaikan jika offline */ }
    }

    if (kodeKec && kecName && !wilayahData.villages[kodeKec]) {
        try {
            const res = await fetch(`https://www.emsifa.com/api-wilayah-indonesia/api/villages/${kodeKec}.json`);
            if (res.ok) {
                const list = await res.json();
                if (Array.isArray(list) && list.length > 0) {
                    wilayahData.villages[kodeKec] = list.map(v => v.name.replace(/\b\w/g, c => c.toUpperCase()));
                }
            }
        } catch (err) { /* abaikan jika offline */ }
    }

    const selectProv = document.getElementById("selectProvinsi");
    if (selectProv.value !== provName || (cityName && document.getElementById("selectKabupaten").value !== cityName) || (kecName && document.getElementById("selectKecamatan").value !== kecName)) {
        selectProv.value = provName;
        handleProvinsiChange(cityName, kecName);
        updateLivePreview();
    }
}

function applyParsedNIK(nik) {
    const result = parseIndonesianNIK(nik);
    const card = document.getElementById("nikParserCard");
    const statusPill = document.getElementById("nikStatusPill");
    const hint = document.getElementById("nikWilayahHint");

    if (!result.valid) {
        state.isNikValid = false;
        document.getElementById("inputTanggalLahir").value = "";
        document.getElementById("inputJenisKelamin").value = "";
        card.classList.add("hidden");
        showNotification(result.error, "error");
        return;
    }

    clearNotification();
    state.isNikValid = true;

    document.getElementById("parsedProv").textContent = result.provName || `Kode ${result.kodeProv}`;
    document.getElementById("parsedKota").textContent = result.cityName || `Kode ${result.kodeKota} (Manual)`;
    document.getElementById("parsedKec").textContent = result.kecName || `Kode ${result.kodeKec} (Manual)`;
    document.getElementById("parsedGender").textContent = result.gender;
    document.getElementById("parsedTgl").textContent = formatIndoDate(result.dateStr);

    const hasPartial = !result.provName || !result.cityName || !result.kecName;
    statusPill.textContent = hasPartial ? "Valid (Parsial)" : "Valid";
    statusPill.className = hasPartial
        ? "px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-100 text-amber-800"
        : "px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-100 text-emerald-700";
    hint.classList.toggle("hidden", !hasPartial);
    card.classList.remove("hidden");

    document.getElementById("inputTanggalLahir").value = result.dateStr;
    document.getElementById("inputJenisKelamin").value = result.gender;

    const selectProv = document.getElementById("selectProvinsi");
    if (result.provName) {
        selectProv.value = result.provName;
        handleProvinsiChange(result.cityName, result.kecName);
        if (!result.cityName || !result.kecName) {
            autoSelectWilayahFromPrefix(nik);
        }
    }
    updateLivePreview();
}

function hideNikInfo() {
    document.getElementById("nikParserCard").classList.add("hidden");
    document.getElementById("inputTanggalLahir").value = "";
    document.getElementById("inputJenisKelamin").value = "";
}

function toggleManualInput(selectEl, inputEl) {
    const isManual = selectEl.value === MANUAL_OPTION_VALUE;
    inputEl.classList.toggle("hidden", !isManual);
    if (!isManual) inputEl.value = "";
}

function handleProvinsiChange(autoCityName = null, autoKecName = null) {
    const selectProv = document.getElementById("selectProvinsi");
    const selectKab = document.getElementById("selectKabupaten");
    const selectKec = document.getElementById("selectKecamatan");
    const selectKel = document.getElementById("selectKelurahan");

    const selectedOpt = selectProv.options[selectProv.selectedIndex];
    const provCode = selectedOpt ? selectedOpt.dataset.code : null;

    selectKab.innerHTML = '<option value="">Pilih Kabupaten/Kota</option>';
    selectKec.innerHTML = '<option value="">Pilih Kecamatan</option>';
    selectKel.innerHTML = '<option value="">Pilih Kelurahan/Desa</option>';

    ["inputKabupatenManual", "inputKecamatanManual", "inputKelurahanManual"].forEach(id => {
        const el = document.getElementById(id);
        el.value = "";
        el.classList.add("hidden");
    });

    selectKab.disabled = !provCode;
    selectKec.disabled = true;
    selectKel.disabled = true;

    if (provCode) {
        Object.entries(wilayahData.cities).forEach(([code, name]) => {
            if (code.startsWith(provCode)) {
                const opt = document.createElement("option");
                opt.value = name;
                opt.textContent = name;
                opt.dataset.code = code;
                selectKab.appendChild(opt);
            }
        });
        const manualOpt = document.createElement("option");
        manualOpt.value = MANUAL_OPTION_VALUE;
        manualOpt.textContent = "+ Lainnya (Ketik Manual)";
        selectKab.appendChild(manualOpt);

        if (autoCityName && Array.from(selectKab.options).some(o => o.value === autoCityName)) {
            selectKab.value = autoCityName;
            handleKabupatenChange(autoKecName);
        }
    }
    updateLivePreview();
}

function handleKabupatenChange(autoKecName = null) {
    const selectKab = document.getElementById("selectKabupaten");
    const manualKab = document.getElementById("inputKabupatenManual");
    const selectKec = document.getElementById("selectKecamatan");
    const selectKel = document.getElementById("selectKelurahan");

    toggleManualInput(selectKab, manualKab);

    const selectedOpt = selectKab.options[selectKab.selectedIndex];
    const cityCode = selectedOpt ? selectedOpt.dataset.code : null;

    selectKec.innerHTML = '<option value="">Pilih Kecamatan</option>';
    selectKel.innerHTML = '<option value="">Pilih Kelurahan/Desa</option>';
    document.getElementById("inputKecamatanManual").classList.add("hidden");
    document.getElementById("inputKecamatanManual").value = "";
    document.getElementById("inputKelurahanManual").classList.add("hidden");
    document.getElementById("inputKelurahanManual").value = "";

    const hasSelection = Boolean(selectKab.value);
    selectKec.disabled = !hasSelection;
    selectKel.disabled = true;

    if (hasSelection) {
        if (cityCode) {
            Object.entries(wilayahData.districts).forEach(([code, name]) => {
                if (code.startsWith(cityCode)) {
                    const opt = document.createElement("option");
                    opt.value = name;
                    opt.textContent = name;
                    opt.dataset.code = code;
                    selectKec.appendChild(opt);
                }
            });
        }
        const manualOpt = document.createElement("option");
        manualOpt.value = MANUAL_OPTION_VALUE;
        manualOpt.textContent = "+ Lainnya (Ketik Manual)";
        selectKec.appendChild(manualOpt);

        if (autoKecName && Array.from(selectKec.options).some(o => o.value === autoKecName)) {
            selectKec.value = autoKecName;
            handleKecamatanChange(true);
        } else if (selectKab.value === MANUAL_OPTION_VALUE) {
            selectKec.value = MANUAL_OPTION_VALUE;
            handleKecamatanChange();
        }
    }
    updateLivePreview();
}

function handleKecamatanChange(autoSelectFirstVillage = false) {
    const selectKec = document.getElementById("selectKecamatan");
    const manualKec = document.getElementById("inputKecamatanManual");
    const selectKel = document.getElementById("selectKelurahan");

    toggleManualInput(selectKec, manualKec);

    const selectedOpt = selectKec.options[selectKec.selectedIndex];
    const kecCode = selectedOpt ? selectedOpt.dataset.code : null;

    selectKel.innerHTML = '<option value="">Pilih Kelurahan/Desa</option>';
    document.getElementById("inputKelurahanManual").classList.add("hidden");
    document.getElementById("inputKelurahanManual").value = "";

    const hasSelection = Boolean(selectKec.value);
    selectKel.disabled = !hasSelection;

    if (hasSelection) {
        if (kecCode && wilayahData.villages[kecCode]) {
            wilayahData.villages[kecCode].forEach(name => {
                const opt = document.createElement("option");
                opt.value = name;
                opt.textContent = name;
                selectKel.appendChild(opt);
            });
            if (autoSelectFirstVillage && wilayahData.villages[kecCode].length > 0) {
                selectKel.value = wilayahData.villages[kecCode][0];
            }
        }
        const manualOpt = document.createElement("option");
        manualOpt.value = MANUAL_OPTION_VALUE;
        manualOpt.textContent = "+ Lainnya (Ketik Manual)";
        selectKel.appendChild(manualOpt);

        if (selectKec.value === MANUAL_OPTION_VALUE || !(kecCode && wilayahData.villages[kecCode])) {
            selectKel.value = MANUAL_OPTION_VALUE;
            handleKelurahanChange();
        }
    }
    updateLivePreview();
}

function handleKelurahanChange() {
    toggleManualInput(
        document.getElementById("selectKelurahan"),
        document.getElementById("inputKelurahanManual")
    );
    updateLivePreview();
}

function getResolvedWilayah() {
    const prov = document.getElementById("selectProvinsi").value;
    const kabSel = document.getElementById("selectKabupaten").value;
    const kab = kabSel === MANUAL_OPTION_VALUE ? document.getElementById("inputKabupatenManual").value.trim() : kabSel;
    const kecSel = document.getElementById("selectKecamatan").value;
    const kec = kecSel === MANUAL_OPTION_VALUE ? document.getElementById("inputKecamatanManual").value.trim() : kecSel;
    const kelSel = document.getElementById("selectKelurahan").value;
    const kel = kelSel === MANUAL_OPTION_VALUE ? document.getElementById("inputKelurahanManual").value.trim() : kelSel;
    return { prov, kab, kec, kel };
}

function goToStep(targetStep) {
    if (targetStep === state.currentStep) return;
    if (targetStep > state.currentStep) {
        for (let s = state.currentStep; s < targetStep; s++) {
            if (!validateStep(s)) return;
        }
    }

    document.querySelectorAll(".form-section").forEach(el => el.classList.remove("active"));
    document.getElementById(`step${targetStep}`).classList.add("active");

    for (let i = 1; i <= 4; i++) {
        const indicator = document.getElementById(`stepIndicator${i}`);
        const numBox = indicator.querySelector("div");
        const label = indicator.querySelector("span");

        if (i < targetStep) {
            numBox.className = "w-8 h-8 rounded-full bg-emerald-500 text-white flex items-center justify-center font-bold text-xs shadow-sm transition-all";
            numBox.innerHTML = '<i data-lucide="check" class="w-4 h-4"></i>';
            label.className = "text-[10px] mt-1.5 font-semibold text-emerald-600";
        } else if (i === targetStep) {
            numBox.className = "w-8 h-8 rounded-full bg-gradient-to-br from-indigo-600 to-violet-600 text-white flex items-center justify-center font-bold text-xs shadow-md scale-105 transition-all";
            numBox.textContent = String(i);
            label.className = "text-[10px] mt-1.5 font-bold text-indigo-600";
        } else {
            numBox.className = "w-8 h-8 rounded-full bg-slate-200 text-slate-500 flex items-center justify-center font-bold text-xs transition-all";
            numBox.textContent = String(i);
            label.className = "text-[10px] mt-1.5 font-medium text-slate-400";
        }
    }

    state.currentStep = targetStep;
    window.scrollTo({ top: 0, behavior: "smooth" });
    if (window.lucide) window.lucide.createIcons();
}

function validateStep(step) {
    clearNotification();
    if (step === 1) {
        const nik = document.getElementById("inputNik").value.trim();
        const parsed = parseIndonesianNIK(nik);
        if (!parsed.valid) {
            showNotification(parsed.error, "error");
            return false;
        }
        const { prov, kab, kec, kel } = getResolvedWilayah();
        if (!prov) { showNotification("Silakan pilih Provinsi terlebih dahulu.", "error"); return false; }
        if (!kab) { showNotification("Silakan pilih atau ketik Kabupaten/Kota terlebih dahulu.", "error"); return false; }
        if (!kec) { showNotification("Silakan pilih atau ketik Kecamatan terlebih dahulu.", "error"); return false; }
        if (!kel) { showNotification("Silakan pilih atau ketik Kelurahan/Desa terlebih dahulu.", "error"); return false; }
    } else if (step === 2) {
        if (!document.getElementById("inputNama").value.trim()) {
            showNotification("Nama Lengkap pemohon wajib diisi.", "error");
            return false;
        }
        if (!document.getElementById("inputTempatLahir").value.trim()) {
            showNotification("Tempat Lahir pemohon wajib diisi.", "error");
            return false;
        }
    } else if (step === 3) {
        if (!document.getElementById("inputAlamat").value.trim()) {
            showNotification("Alamat tempat tinggal wajib diisi.", "error");
            return false;
        }
    }
    return true;
}

function processPhotoFile(file) {
    const validTypes = ["image/jpeg", "image/jpg", "image/png"];
    if (!validTypes.includes(file.type)) {
        showNotification("Format foto harus berupa JPG atau PNG.", "error");
        return;
    }
    if (file.size > 2 * 1024 * 1024) {
        showNotification("Ukuran pas foto maksimal adalah 2 MB.", "error");
        return;
    }

    const reader = new FileReader();
    reader.onload = (ev) => {
        state.selectedPhotoDataUrl = ev.target.result;
        state.selectedPhotoName = file.name;
        state.selectedPhotoSize = `${(file.size / 1024).toFixed(1)} KB`;
        applyPhotoToUI();
    };
    reader.readAsDataURL(file);
}

function applyPhotoToUI() {
    const hasPhoto = Boolean(state.selectedPhotoDataUrl);
    document.getElementById("dropZone").classList.toggle("hidden", hasPhoto);
    document.getElementById("fileInfoBox").classList.toggle("hidden", !hasPhoto);

    if (hasPhoto) {
        document.getElementById("fileName").textContent = state.selectedPhotoName || "Pas Foto Tersimpan";
        document.getElementById("fileSize").textContent = state.selectedPhotoSize || "Gambar tersemat";
        document.getElementById("uploadedPreviewImg").src = state.selectedPhotoDataUrl;
    } else {
        document.getElementById("uploadedPreviewImg").src = "";
    }
    updateLivePreview();
}

function processTemplateFile(file) {
    const validTypes = ["image/jpeg", "image/jpg", "image/png"];
    if (!validTypes.includes(file.type)) {
        showNotification("Format template harus berupa JPG atau PNG.", "error");
        return;
    }
    const reader = new FileReader();
    reader.onload = (ev) => {
        const img = new Image();
        img.onload = () => {
            state.customTemplateImg = img;
            state.textTheme = "dark";
            document.getElementById("textThemeLabel").textContent = "Teks Gelap";
            document.getElementById("btnResetTemplate").classList.remove("hidden");
            document.getElementById("calibrationBox").classList.remove("hidden");
            updateLivePreview();
            showNotification("Template kartu kustom berhasil diterapkan ke kanvas.", "success");
        };
        img.src = ev.target.result;
    };
    reader.readAsDataURL(file);
}

function handleCalibrationInput() {
    const x = parseInt(document.getElementById("calOffsetX").value, 10) || 0;
    const y = parseInt(document.getElementById("calOffsetY").value, 10) || 0;
    const s = parseInt(document.getElementById("calFontScale").value, 10) || 100;
    const w = parseInt(document.getElementById("calFontWeight").value, 10) || 500;
    const b = (parseInt(document.getElementById("calBlurPx").value, 10) || 0) / 10;
    const o = parseInt(document.getElementById("calOpacity").value, 10) || 100;
    state.calibration = {
        offsetX: x,
        offsetY: y,
        fontScale: s / 100,
        fontWeight: w === 500 ? 0 : w,
        blurPx: b,
        opacity: o / 100
    };
    document.getElementById("calXVal").textContent = String(x);
    document.getElementById("calYVal").textContent = String(y);
    document.getElementById("calScaleVal").textContent = `${s}%`;
    document.getElementById("calWeightVal").textContent = w === 500 ? "Normal" : String(w);
    document.getElementById("calBlurVal").textContent = `${b.toFixed(1)}px`;
    document.getElementById("calOpacityVal").textContent = `${o}%`;
    updateLivePreview();
}

function resetCalibration() {
    document.getElementById("calOffsetX").value = 0;
    document.getElementById("calOffsetY").value = 0;
    document.getElementById("calFontScale").value = 100;
    document.getElementById("calFontWeight").value = 500;
    document.getElementById("calBlurPx").value = 0;
    document.getElementById("calOpacity").value = 100;
    handleCalibrationInput();
}

function toggleTextTheme() {
    state.textTheme = state.textTheme === "light" ? "dark" : "light";
    document.getElementById("textThemeLabel").textContent = state.textTheme === "light" ? "Teks Terang" : "Teks Gelap";
    updateLivePreview();
}

function resetCustomTemplate() {
    state.customTemplateImg = null;
    state.textTheme = "light";
    document.getElementById("textThemeLabel").textContent = "Teks Terang";
    document.getElementById("inputTemplateFile").value = "";
    document.getElementById("btnResetTemplate").classList.add("hidden");
    document.getElementById("calibrationBox").classList.add("hidden");
    resetCalibration();
    updateLivePreview();
    showNotification("Kembali ke template kartu bawaan.", "info");
}

function clearFoto() {
    state.selectedPhotoDataUrl = "";
    state.selectedPhotoName = "";
    state.selectedPhotoSize = "";
    document.getElementById("fileFoto").value = "";
    applyPhotoToUI();
}

function setDefaultTanggalTerbit() {
    const el = document.getElementById("inputTanggalTerbit");
    if (el && !el.value) {
        const now = new Date();
        const yyyy = now.getFullYear();
        const mm = String(now.getMonth() + 1).padStart(2, "0");
        const dd = String(now.getDate()).padStart(2, "0");
        el.value = `${yyyy}-${mm}-${dd}`;
    }
}

function formatDateDDMMYYYY(dateStr) {
    if (!dateStr || !dateStr.includes("-")) return "-";
    const [y, m, d] = dateStr.split("-");
    return `${d}-${m}-${y}`;
}

function setupSignaturePad() {
    const pad = document.getElementById("signaturePad");
    const clearBtn = document.getElementById("btnClearSignature");
    if (!pad) return;
    const ctx = pad.getContext("2d");
    let drawing = false;

    const getClientPoint = (ev) => {
        if (ev.touches && ev.touches.length > 0) {
            return { cx: ev.touches[0].clientX, cy: ev.touches[0].clientY };
        }
        return { cx: ev.clientX, cy: ev.clientY };
    };

    const getPos = (ev) => {
        const rect = pad.getBoundingClientRect();
        const { cx, cy } = getClientPoint(ev);
        return {
            x: (cx - rect.left) * (pad.width / (rect.width || pad.width)),
            y: (cy - rect.top) * (pad.height / (rect.height || pad.height))
        };
    };

    const startDraw = (ev) => {
        if (ev.cancelable) ev.preventDefault();
        drawing = true;
        ctx.strokeStyle = "#0f172a";
        ctx.lineWidth = 5;
        ctx.lineCap = "round";
        ctx.lineJoin = "round";
        const p = getPos(ev);
        ctx.beginPath();
        ctx.moveTo(p.x, p.y);
        ctx.lineTo(p.x + 0.1, p.y + 0.1);
        ctx.stroke();
    };

    const moveDraw = (ev) => {
        if (!drawing) return;
        if (ev.cancelable) ev.preventDefault();
        const p = getPos(ev);
        ctx.lineTo(p.x, p.y);
        ctx.stroke();
    };

    const endDraw = () => {
        if (!drawing) return;
        drawing = false;
        state.signatureDataUrl = pad.toDataURL("image/png");
        updateLivePreview();
    };

    pad.addEventListener("mousedown", startDraw);
    pad.addEventListener("mousemove", moveDraw);
    window.addEventListener("mouseup", endDraw);

    pad.addEventListener("touchstart", startDraw, { passive: false });
    pad.addEventListener("touchmove", moveDraw, { passive: false });
    pad.addEventListener("touchend", endDraw);
    pad.addEventListener("touchcancel", endDraw);

    const uploadBtn = document.getElementById("btnUploadSignature");
    const uploadInput = document.getElementById("inputSignatureFile");
    if (uploadBtn && uploadInput) {
        uploadBtn.addEventListener("click", () => uploadInput.click());
        uploadInput.addEventListener("change", (ev) => {
            const file = ev.target.files && ev.target.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = (r) => {
                const img = new Image();
                img.onload = () => {
                    ctx.clearRect(0, 0, pad.width, pad.height);
                    ctx.drawImage(img, 0, 0, pad.width, pad.height);
                    state.signatureDataUrl = r.target.result;
                    updateLivePreview();
                };
                img.src = r.target.result;
            };
            reader.readAsDataURL(file);
        });
    }

    if (clearBtn) {
        clearBtn.addEventListener("click", () => {
            ctx.clearRect(0, 0, pad.width, pad.height);
            if (uploadInput) uploadInput.value = "";
            state.signatureDataUrl = "";
            updateLivePreview();
        });
    }
}

function updateLivePreview() {
    const nikVal = document.getElementById("inputNik").value.trim();
    const { prov, kab, kec, kel } = getResolvedWilayah();

    const tempat = document.getElementById("inputTempatLahir").value.trim();
    const tgl = document.getElementById("inputTanggalLahir").value;
    const rtRaw = document.getElementById("inputRt").value.trim();
    const rwRaw = document.getElementById("inputRw").value.trim();
    const rtFmt = rtRaw ? rtRaw.padStart(3, "0") : "000";
    const rwFmt = rwRaw ? rwRaw.padStart(3, "0") : "000";

    const cardData = {
        provinsiLabel: prov ? `PROVINSI ${prov}` : "PROVINSI -",
        kabupatenLabel: kab || "KABUPATEN / KOTA -",
        nik: nikVal || "----------------",
        nama: document.getElementById("inputNama").value.trim() || "-",
        ttl: (tempat || tgl) ? `${tempat || "-"}, ${formatIndoDate(tgl)}` : "-",
        jenisKelamin: document.getElementById("inputJenisKelamin").value || "-",
        golDarah: document.getElementById("selectGolDarah").value || "-",
        alamat: document.getElementById("inputAlamat").value.trim() || "-",
        rtRw: `${rtFmt}/${rwFmt}`,
        kelurahan: kel || "-",
        kecamatan: kec || "-",
        agama: document.getElementById("selectAgama").value || "-",
        statusPerkawinan: document.getElementById("selectStatus").value || "-",
        pekerjaan: document.getElementById("inputPekerjaan").value.trim() || "-",
        kewarganegaraan: document.getElementById("selectWarga").value || "WNI",
        kotaTerbit: kab || "KOTA / KABUPATEN",
        tanggalTerbit: formatDateDDMMYYYY(document.getElementById("inputTanggalTerbit").value),
        photoDataUrl: state.selectedPhotoDataUrl || "",
        signatureDataUrl: state.signatureDataUrl || ""
    };

    const canvasEl = document.getElementById("ktpCanvas");
    if (typeof renderKtpCanvas === "function") {
        renderKtpCanvas(canvasEl, cardData, state.customTemplateImg, state.textTheme, state.calibration);
    }
}

function resetFullForm() {
    document.getElementById("ktpForm").reset();
    setDefaultTanggalTerbit();
    document.getElementById("nikCharCounter").textContent = "0 / 16";
    state.isNikValid = false;
    state.editingId = null;
    document.getElementById("editModeBanner").classList.add("hidden");
    document.getElementById("submitBtnLabel").textContent = "Simpan Data";

    hideNikInfo();
    handleProvinsiChange();
    clearFoto();
    const sigPad = document.getElementById("signaturePad");
    if (sigPad) sigPad.getContext("2d").clearRect(0, 0, sigPad.width, sigPad.height);
    state.signatureDataUrl = "";
    updateLivePreview();
    goToStep(1);
}

function handleFormSubmit(event) {
    event.preventDefault();
    if (!validateStep(1) || !validateStep(2) || !validateStep(3)) return;

    const { prov, kab, kec, kel } = getResolvedWilayah();
    const rtRaw = document.getElementById("inputRt").value.trim();
    const rwRaw = document.getElementById("inputRw").value.trim();

    const record = {
        id: state.editingId || `ktp_${Date.now()}`,
        nik: document.getElementById("inputNik").value.trim(),
        provinsi: prov,
        kabupaten: kab,
        kecamatan: kec,
        kelurahan: kel,
        tanggalLahir: document.getElementById("inputTanggalLahir").value,
        jenisKelamin: document.getElementById("inputJenisKelamin").value,
        nama: document.getElementById("inputNama").value.trim(),
        tempatLahir: document.getElementById("inputTempatLahir").value.trim(),
        golDarah: document.getElementById("selectGolDarah").value,
        alamat: document.getElementById("inputAlamat").value.trim(),
        rt: rtRaw ? rtRaw.padStart(3, "0") : "000",
        rw: rwRaw ? rwRaw.padStart(3, "0") : "000",
        agama: document.getElementById("selectAgama").value || "-",
        statusPerkawinan: document.getElementById("selectStatus").value || "-",
        pekerjaan: document.getElementById("inputPekerjaan").value.trim() || "-",
        kewarganegaraan: document.getElementById("selectWarga").value || "WNI",
        kotaTerbit: kab || "KOTA / KABUPATEN",
        tanggalTerbit: formatDateDDMMYYYY(document.getElementById("inputTanggalTerbit").value),
        photoDataUrl: state.selectedPhotoDataUrl || "",
        signatureDataUrl: state.signatureDataUrl || "",
        savedAt: new Date().toISOString()
    };

    const existingIdx = state.savedRecords.findIndex(r => r.id === record.id);
    if (existingIdx >= 0) {
        state.savedRecords[existingIdx] = record;
    } else {
        state.savedRecords.unshift(record);
    }

    const savedToDisk = persistRecords(state.savedRecords);
    updateSavedBadge();
    renderSavedList();

    const actionLabel = existingIdx >= 0 ? "diperbarui" : "disimpan";
    const storageNote = savedToDisk ? "ke penyimpanan ponsel" : "di memori sesi ini";
    resetFullForm();
    showNotification(`Data ${record.nama} berhasil ${actionLabel} ${storageNote}. Cek tab Tersimpan.`, "success");
}

function fillDemoData(preset) {
    resetFullForm();
    if (preset === "palembang") {
        document.getElementById("inputNik").value = "1671065508980001";
        handleNikInput({ target: document.getElementById("inputNik") });
        document.getElementById("selectKelurahan").value = "Sungai Pangeran";
        document.getElementById("inputNama").value = "SITI RAHMAWATI";
        document.getElementById("inputTempatLahir").value = "PALEMBANG";
        document.getElementById("selectGolDarah").value = "O";
        document.getElementById("inputAlamat").value = "JL. JEND. SUDIRMAN NO. 45";
        document.getElementById("inputRt").value = "004";
        document.getElementById("inputRw").value = "002";
        document.getElementById("selectAgama").value = "ISLAM";
        document.getElementById("selectStatus").value = "BELUM KAWIN";
        document.getElementById("inputPekerjaan").value = "KARYAWAN SWASTA";
        document.getElementById("selectWarga").value = "WNI";
    } else {
        document.getElementById("inputNik").value = "3174011708950002";
        handleNikInput({ target: document.getElementById("inputNik") });
        document.getElementById("selectKelurahan").value = "Tebet Barat";
        document.getElementById("inputNama").value = "BUDI SANTOSO";
        document.getElementById("inputTempatLahir").value = "JAKARTA";
        document.getElementById("selectGolDarah").value = "A";
        document.getElementById("inputAlamat").value = "JL. TEBET BARAT DALAM NO. 18";
        document.getElementById("inputRt").value = "002";
        document.getElementById("inputRw").value = "005";
        document.getElementById("selectAgama").value = "ISLAM";
        document.getElementById("selectStatus").value = "KAWIN";
        document.getElementById("inputPekerjaan").value = "WIRASWASTA";
        document.getElementById("selectWarga").value = "WNI";
    }
    updateLivePreview();
    showNotification("Data contoh berhasil diisikan. Ketuk 'Kartu' di pojok kanan atas untuk melihat pratinjau.", "success");
}

function updateSavedBadge() {
    document.getElementById("savedCountBadge").textContent = String(state.savedRecords.length);
}

function renderSavedList() {
    const container = document.getElementById("savedListContainer");
    const emptyState = document.getElementById("savedEmptyState");
    const query = document.getElementById("searchSavedInput").value.trim().toLowerCase();

    const filtered = state.savedRecords.filter(r =>
        r.nik.includes(query) ||
        r.nama.toLowerCase().includes(query) ||
        r.provinsi.toLowerCase().includes(query) ||
        r.kabupaten.toLowerCase().includes(query)
    );

    container.innerHTML = "";
    if (filtered.length === 0) {
        emptyState.classList.remove("hidden");
        return;
    }
    emptyState.classList.add("hidden");

    filtered.forEach(rec => {
        const card = document.createElement("div");
        card.className = "p-4 rounded-2xl border border-slate-200 bg-white shadow-xs space-y-3";
        card.innerHTML = `
            <div class="flex items-start justify-between gap-2">
                <div class="min-w-0">
                    <p class="text-xs font-mono font-bold text-indigo-600">${escapeHtml(rec.nik)}</p>
                    <h3 class="text-sm font-bold text-slate-800 truncate mt-0.5">${escapeHtml(rec.nama)}</h3>
                    <p class="text-xs text-slate-500 mt-0.5 truncate">${escapeHtml(rec.kabupaten)}, ${escapeHtml(rec.provinsi)}</p>
                    <p class="text-[11px] text-slate-400 mt-1">${escapeHtml(rec.jenisKelamin)} • ${escapeHtml(formatIndoDate(rec.tanggalLahir))}</p>
                </div>
                <span class="px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200 shrink-0">${escapeHtml(rec.kewarganegaraan)}</span>
            </div>
            <div class="grid grid-cols-2 gap-2 pt-2 border-t border-slate-100">
                <button type="button" data-load-id="${escapeHtml(rec.id)}" class="btn-load-rec min-h-[40px] px-3 py-2 rounded-xl text-xs font-bold bg-indigo-50 text-indigo-700 active:bg-indigo-100 flex items-center justify-center gap-1.5">
                    <i data-lucide="eye" class="w-3.5 h-3.5"></i> Muat / Sunting
                </button>
                <button type="button" data-del-id="${escapeHtml(rec.id)}" class="btn-del-rec min-h-[40px] px-3 py-2 rounded-xl text-xs font-bold bg-rose-50 text-rose-600 active:bg-rose-100 flex items-center justify-center gap-1.5">
                    <i data-lucide="trash-2" class="w-3.5 h-3.5"></i> Hapus
                </button>
            </div>
        `;
        container.appendChild(card);
    });

    container.querySelectorAll(".btn-load-rec").forEach(btn => {
        btn.addEventListener("click", () => loadRecordIntoForm(btn.dataset.loadId));
    });
    container.querySelectorAll(".btn-del-rec").forEach(btn => {
        btn.addEventListener("click", () => openDeleteModal(btn.dataset.delId));
    });
    if (window.lucide) window.lucide.createIcons();
}

function loadRecordIntoForm(id) {
    const rec = state.savedRecords.find(r => r.id === id);
    if (!rec) return;

    resetFullForm();
    state.editingId = rec.id;
    document.getElementById("editModeBanner").classList.remove("hidden");
    document.getElementById("submitBtnLabel").textContent = "Perbarui Data";

    document.getElementById("inputNik").value = rec.nik;
    handleNikInput({ target: document.getElementById("inputNik") });

    document.getElementById("selectProvinsi").value = rec.provinsi;
    handleProvinsiChange();
    const selectKab = document.getElementById("selectKabupaten");
    if (Array.from(selectKab.options).some(o => o.value === rec.kabupaten)) {
        selectKab.value = rec.kabupaten;
    } else {
        selectKab.value = MANUAL_OPTION_VALUE;
        document.getElementById("inputKabupatenManual").value = rec.kabupaten;
    }
    handleKabupatenChange();

    const selectKec = document.getElementById("selectKecamatan");
    if (Array.from(selectKec.options).some(o => o.value === rec.kecamatan)) {
        selectKec.value = rec.kecamatan;
    } else {
        selectKec.value = MANUAL_OPTION_VALUE;
        document.getElementById("inputKecamatanManual").value = rec.kecamatan;
    }
    handleKecamatanChange();

    const selectKel = document.getElementById("selectKelurahan");
    if (Array.from(selectKel.options).some(o => o.value === rec.kelurahan)) {
        selectKel.value = rec.kelurahan;
    } else {
        selectKel.value = MANUAL_OPTION_VALUE;
        document.getElementById("inputKelurahanManual").value = rec.kelurahan;
    }
    handleKelurahanChange();

    document.getElementById("inputNama").value = rec.nama;
    document.getElementById("inputTempatLahir").value = rec.tempatLahir;
    document.getElementById("selectGolDarah").value = rec.golDarah || "-";
    document.getElementById("inputAlamat").value = rec.alamat;
    document.getElementById("inputRt").value = rec.rt;
    document.getElementById("inputRw").value = rec.rw;
    document.getElementById("selectAgama").value = rec.agama === "-" ? "" : rec.agama;
    document.getElementById("selectStatus").value = rec.statusPerkawinan === "-" ? "" : rec.statusPerkawinan;
    document.getElementById("inputPekerjaan").value = rec.pekerjaan === "-" ? "" : rec.pekerjaan;
    document.getElementById("selectWarga").value = rec.kewarganegaraan || "WNI";

    if (rec.photoDataUrl) {
        state.selectedPhotoDataUrl = rec.photoDataUrl;
        state.selectedPhotoName = "Pas Foto Tersimpan";
        state.selectedPhotoSize = "Dimuat dari riwayat";
        applyPhotoToUI();
    }

    updateLivePreview();
    switchTab("form");
    showNotification(`Data ${rec.nama} dimuat ke formulir & pratinjau.`, "success");
}

function openDeleteModal(id) {
    const rec = state.savedRecords.find(r => r.id === id);
    if (!rec) return;
    state.pendingDeleteId = id;
    document.getElementById("deleteModalDesc").textContent = `Hapus data ${rec.nama} (NIK ${rec.nik}) dari daftar tersimpan?`;
    document.getElementById("deleteModal").classList.remove("hidden");
}

function closeDeleteModal() {
    state.pendingDeleteId = null;
    document.getElementById("deleteModal").classList.add("hidden");
}

function confirmDeleteRecord() {
    if (!state.pendingDeleteId) return;
    state.savedRecords = state.savedRecords.filter(r => r.id !== state.pendingDeleteId);
    persistRecords(state.savedRecords);
    if (state.editingId === state.pendingDeleteId) resetFullForm();
    closeDeleteModal();
    updateSavedBadge();
    renderSavedList();
    showNotification("Data berhasil dihapus dari daftar tersimpan.", "success");
}

function downloadKtpCard() {
    const nikVal = document.getElementById("inputNik").value.trim();
    const fileName = `Simulasi_KTP_${nikVal || "Draf"}.png`;
    const canvasEl = document.getElementById("ktpCanvas");
    if (!canvasEl) return;

    if (window.AndroidBridge && typeof window.AndroidBridge.savePng === "function") {
        try {
            const dataUrl = exportCanvasToPngDataUrl(canvasEl);
            window.AndroidBridge.savePng(dataUrl, fileName);
            showNotification("Kartu PNG disimpan ke folder Download ponsel.", "success");
            return;
        } catch (err) { /* fallback ke browser */ }
    }

    const openResultModal = (hrefUrl) => {
        const modal = document.getElementById("pngResultModal");
        const img = document.getElementById("pngResultImg");
        const dlLink = document.getElementById("pngFallbackDownloadLink");
        if (img) img.src = canvasEl.toDataURL("image/png");
        if (dlLink) {
            dlLink.href = hrefUrl;
            dlLink.download = fileName;
        }
        if (modal) modal.classList.remove("hidden");
    };

    try {
        if (typeof canvasEl.toBlob === "function") {
            canvasEl.toBlob((blob) => {
                if (!blob) {
                    const dataUrl = exportCanvasToPngDataUrl(canvasEl);
                    openResultModal(dataUrl);
                    return;
                }
                const blobUrl = URL.createObjectURL(blob);
                const link = document.createElement("a");
                link.href = blobUrl;
                link.download = fileName;
                document.body.appendChild(link);
                link.click();
                link.remove();
                openResultModal(blobUrl);
                showNotification("Kartu PNG siap disimpan (1011×638).", "success");
            }, "image/png");
        } else {
            const dataUrl = exportCanvasToPngDataUrl(canvasEl);
            const link = document.createElement("a");
            link.href = dataUrl;
            link.download = fileName;
            document.body.appendChild(link);
            link.click();
            link.remove();
            openResultModal(dataUrl);
            showNotification("Kartu PNG siap disimpan (1011×638).", "success");
        }
    } catch (err) {
        showNotification("Gagal mengekspor pratinjau kartu ke gambar PNG.", "error");
    }
}

function exportRecordsAsJson() {
    if (state.savedRecords.length === 0) {
        showNotification("Belum ada data tersimpan untuk diekspor.", "error");
        return;
    }
    const cleaned = state.savedRecords.map(({ photoDataUrl, ...rest }) => rest);
    triggerBlobDownload(JSON.stringify(cleaned, null, 2), "data_ktp_tersimpan.json", "application/json");
    showNotification("Data berhasil diekspor ke format JSON.", "success");
}

function exportRecordsAsCsv() {
    if (state.savedRecords.length === 0) {
        showNotification("Belum ada data tersimpan untuk diekspor.", "error");
        return;
    }
    const headers = ["NIK", "Nama", "Tempat Lahir", "Tanggal Lahir", "Jenis Kelamin", "Gol Darah", "Alamat", "RT", "RW", "Kelurahan", "Kecamatan", "Kabupaten/Kota", "Provinsi", "Agama", "Status Perkawinan", "Pekerjaan", "Kewarganegaraan"];
    const rows = state.savedRecords.map(r => [
        `'${r.nik}`, r.nama, r.tempatLahir, r.tanggalLahir, r.jenisKelamin, r.golDarah,
        r.alamat, r.rt, r.rw, r.kelurahan, r.kecamatan, r.kabupaten, r.provinsi,
        r.agama, r.statusPerkawinan, r.pekerjaan, r.kewarganegaraan
    ].map(v => `"${String(v || "").replace(/"/g, '""')}"`).join(","));
    const csvContent = [headers.join(","), ...rows].join("\n");
    triggerBlobDownload(csvContent, "data_ktp_tersimpan.csv", "text/csv;charset=utf-8;");
    showNotification("Data berhasil diekspor ke format CSV.", "success");
}

function triggerBlobDownload(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function escapeHtml(str) {
    return String(str || "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function showNotification(message, type = "success") {
    const banner = document.getElementById("alertBanner");
    const iconBox = document.getElementById("alertIcon");
    const textBox = document.getElementById("alertText");

    banner.className = "mx-4 mt-3 p-3.5 rounded-2xl border flex items-start justify-between gap-2.5 shadow-sm " + (
        type === "success"
            ? "bg-emerald-50 text-emerald-800 border-emerald-200"
            : "bg-rose-50 text-rose-800 border-rose-200"
    );
    textBox.textContent = message;
    iconBox.innerHTML = type === "success"
        ? '<i data-lucide="check-circle-2" class="w-4 h-4 text-emerald-600"></i>'
        : '<i data-lucide="alert-triangle" class="w-4 h-4 text-rose-600"></i>';
    banner.classList.remove("hidden");
    if (window.lucide) window.lucide.createIcons();
}

function clearNotification() {
    document.getElementById("alertBanner").classList.add("hidden");
}
