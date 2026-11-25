const STORAGE_KEY = "disdukcapil_ktp_records_v2";
let memoryFallbackRecords = [];

function loadSavedRecords() {
    try {
        const raw = window.localStorage.getItem(STORAGE_KEY);
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
        return [...memoryFallbackRecords];
    }
}

function persistRecords(records) {
    memoryFallbackRecords = [...records];
    try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
        return true;
    } catch (e) {
        return false;
    }
}
