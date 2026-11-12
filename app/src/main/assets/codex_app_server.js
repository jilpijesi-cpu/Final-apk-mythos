'use strict';

// One short-lived Codex app-server session per Mythos completion; no Python or fixed HTTP listener.
const cp = require('child_process');
const net = require('net');

// --- Pengerasan (v81) -------------------------------------------------------------------------------------------
// Mythos hanya memakai Codex sebagai model bahasa. Argumen -c bersifat FAIL-CLOSED (v82): bila versi Codex menolaknya,
// sesi dihentikan dengan galat jelas, TIDAK dijalankan ulang tanpa pengerasan. Pemutus (tripwire) tetap jadi lapisan kedua.
const fs = require('fs');
const os = require('os');
const path = require('path');
const HARDENED_ARGS = ['-c', 'features.shell_tool=false', '-c', 'project_doc_max_bytes=0', '-c', 'history.persistence="none"'];
// patch9: opsi tambahan yang mematikan pencarian web bawaan Codex di tingkat konfigurasi (nama opsi berbeda antar versi Codex).
// Dicoba berurutan: bila Codex menolak satu tingkat saat start, tingkat berikutnya dipakai. Tingkat terakhir = hanya pengerasan inti.
// Pengerasan inti (HARDENED_ARGS) TIDAK PERNAH dilonggarkan; pemutus (tripwire) tetap lapisan kedua.
const EXTRA_ARG_TIERS = [
  ['-c', 'web_search="disabled"', '-c', 'tools.web_search=false', '-c', 'features.web_search_request=false'],
  ['-c', 'web_search="disabled"'],
  []
];
let argTier = 0;
const MAX_NATIVE_RETRIES = 2; // total 3 percobaan per pesan
const BLOCKED_ITEMS = new Set([
  'commandExecution', 'command_execution', 'fileChange', 'file_change',
  'mcpToolCall', 'mcp_tool_call', 'webSearch', 'web_search'
]);
const KEEP_SESSIONS = process.env.MYTHOS_KEEP_CODEX_SESSIONS === '1';
function pad2(n) { return String(n).padStart(2, '0'); }
function purgeThread(threadId) {
  if (KEEP_SESSIONS || !/^[0-9A-Za-z-]{8,64}$/.test(String(threadId || ''))) return;
  setTimeout(() => {
    try {
      const root = path.join(process.env.CODEX_HOME || path.join(os.homedir(), '.codex'), 'sessions');
      const days = new Set();
      for (const off of [0, -1]) {
        const d = new Date(Date.now() + off * 86400000);
        days.add([d.getFullYear(), pad2(d.getMonth() + 1), pad2(d.getDate())].join('/'));
        days.add([d.getUTCFullYear(), pad2(d.getUTCMonth() + 1), pad2(d.getUTCDate())].join('/'));
      }
      for (const day of days) {
        const dir = path.join(root, day);
        let names = [];
        try { names = fs.readdirSync(dir); } catch (_) { continue; }
        for (const name of names) {
          if (name.endsWith('-' + threadId + '.jsonl')) { try { fs.unlinkSync(path.join(dir, name)); } catch (_) {} }
        }
      }
    } catch (_) {}
  }, 1500);
}
const PORT = Number(process.argv[2] || 0);
const TOKEN = String(process.argv[3] || '');
// Mode base.apk: prompt dibawa sebagai argumen ke-4 (base64) sehingga soket hanyalah jalur tambahan yang tidak fatal.
const PROMPT_B64 = String(process.argv[4] || '');
const ARGV_PROMPT = PROMPT_B64.length > 0;
const TIMEOUT_MS = 15 * 60 * 1000;
let socket;
let socketBuffer = '';
let seq = 0;
let sockReady = false;
let sockQueue = [];
let inputBuffer = '';
let child;
let started = false;
let initDone = false;
let ended = false;
let threadId = '';
// patch8: tool bawaan Codex tidak lagi membunuh seluruh giliran. Giliran dihentikan (turn/interrupt), lalu diulang SEKALI
// di thread baru dengan pengingat keras; peristiwa dari thread lama diabaikan.
let nativeRetries = 0;
let retryPrompt = '';
let currentTurnId = '';
const deadThreads = new Set();
function nativeReminder(level, blockedType) {
  const lines = [
    'KOREKSI (' + level + '): percobaan sebelumnya memanggil tool bawaan Codex (' + (blockedType || 'mcpToolCall/commandExecution/fileChange/webSearch') + ') dan DIBATALKAN.',
    'Kamu TIDAK punya tool bawaan di sini: tidak ada pencarian web bawaan, tidak ada shell, tidak ada baca/tulis file bawaan. Jangan memanggil tool lewat mekanisme Codex/MCP.',
    'PEMETAAN: mencari di web -> <tool_use>{\"name\": \"WebSearch\", \"input\": {\"query\": \"...\"}}</tool_use>; membuka halaman -> WebFetch {\"url\": \"...\"}; membaca file/lampiran -> Read/Glob/Grep. Semuanya ditulis sebagai TEKS BIASA di jawabanmu; Mythos yang menjalankannya.',
    'Mulai jawaban dengan satu kalimat singkat, lalu LANGSUNG tulis blok <tool_use> bila perlu mencari/membaca. Jangan hanya menjanjikan akan melakukannya.'
  ];
  if (level >= 2) lines.push('PERINGATAN TERAKHIR: sekali lagi memakai tool bawaan, sesi dibatalkan. Tulis <tool_use> sebagai teks, atau jawab langsung tanpa alat.');
  lines.push('');
  return lines.join('\n');
}
// Ubah percobaan tool bawaan menjadi pemanggilan alat Mythos yang setara (hanya pencarian web; sisanya tidak punya padanan aman).
function nativeToMythosCall(item) {
  try {
    if (!item || (item.type !== 'webSearch' && item.type !== 'web_search')) return '';
    const a = item.action || {};
    let q = item.query || a.query || (Array.isArray(a.queries) && a.queries[0]) || '';
    q = String(q || '').replace(/\s+/g, ' ').trim();
    if (!q || q.length > 300) return '';
    return '\n<tool_use>' + JSON.stringify({status: 'Mencari di web', name: 'WebSearch', input: {query: q}}) + '</tool_use>';
  } catch (_) { return ''; }
}
let answer = '';
let currentText = '';
let stderr = '';
let timer;
let shutdownTimer;
let hardKillTimer;

// Tiap baris protokol bernomor urut n dan ditulis ke stdout (jalur cadangan yang dibaca aplikasi dari hasil Termux)
// DAN ke soket (aliran langsung). Aplikasi membuang baris ganda berdasarkan n, jadi soket putus tidak menghilangkan data.
function emit(event) {
  seq += 1;
  const line = JSON.stringify(Object.assign({n: seq}, event)) + '\n';
  try { process.stdout.write(line); } catch (_) {}
  if (!socket || socket.destroyed) return;
  if (sockReady) { try { socket.write(line); } catch (_) {} }
  else if (sockQueue.length < 2000) sockQueue.push(line);
}

function signalProcessGroup(signal) {
  if (!child || !child.pid) return;
  try { process.kill(-child.pid, signal); }
  catch (_) {
    if (child.exitCode === null) {
      try { child.kill(signal); } catch (_) {}
    }
  }
}
function stopProcessGroup() {
  if (!child || !child.pid) return;
  signalProcessGroup('SIGTERM');
  try {
    process.kill(-child.pid, 0);
    if (hardKillTimer) clearTimeout(hardKillTimer);
    hardKillTimer = setTimeout(() => {
      hardKillTimer = null;
      signalProcessGroup('SIGKILL');
    }, 1500);
  } catch (_) {}
}
function scheduleProcessGroupStop() {
  if (!child || !child.pid || shutdownTimer) return;
  shutdownTimer = setTimeout(() => {
    shutdownTimer = null;
    stopProcessGroup();
  }, 2000);
}

function send(obj) {
  if (child && child.stdin && !child.stdin.destroyed) child.stdin.write(JSON.stringify(obj) + '\n');
}
function finish(error, retryable) {
  if (ended) return;
  ended = true;
  if (timer) clearTimeout(timer);
  purgeThread(threadId);
  if (error) {
    emit({type: 'error', message: String(error).slice(0, 8000), retryable: !!retryable});
    try { process.stderr.write(String(error).slice(0, 2000) + '\n'); } catch (_) {}
  }
  process.exitCode = error ? 1 : 0;
  emit({type: 'done'});
  if (child && child.stdin && !child.stdin.destroyed) {
    try { child.stdin.end(); } catch (_) {}
  }
  scheduleProcessGroupStop();
  if (socket && !socket.destroyed) socket.end();
}
function cleanError(value) {
  if (!value) return 'Codex app-server gagal.';
  if (typeof value === 'string') return value;
  return value.message || JSON.stringify(value);
}
function startSession(request) {
  if (started) return;
  started = true;
  const prompt = typeof request.prompt === 'string' ? request.prompt : '';
  if (!prompt.trim()) { finish('Prompt Codex kosong.'); return; }
  timer = setTimeout(() => {
    stopProcessGroup();
    finish('Codex app-server melewati batas waktu 15 menit.');
  }, TIMEOUT_MS);
  spawnChild(prompt);
}
function spawnChild(prompt) {
  const args = HARDENED_ARGS.concat(EXTRA_ARG_TIERS[argTier], ['app-server', '--listen', 'stdio://']);
  child = cp.spawn('codex', args, {stdio: ['pipe', 'pipe', 'pipe'], detached: true});
  const mine = child;
  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdin.on('error', () => {}); // EPIPE bila Codex keluar lebih dulu; ditangani lewat event 'close' (galat fail-closed)
  child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-8000); });
  child.stdout.on('data', chunk => {
    inputBuffer += chunk;
    let nl;
    while ((nl = inputBuffer.indexOf('\n')) >= 0) {
      const line = inputBuffer.slice(0, nl);
      inputBuffer = inputBuffer.slice(nl + 1);
      if (!line.trim()) continue;
      try { handle(JSON.parse(line), prompt); }
      catch (e) { finish('Respons protokol Codex tidak valid: ' + e.message); }
    }
  });
  child.on('error', e => finish('Tidak dapat menjalankan Codex app-server: ' + e.message));
  child.on('close', code => {
    if (!ended && !initDone && child === mine && argTier < EXTRA_ARG_TIERS.length - 1) {
      // patch9: opsi tambahan (mis. web_search) ditolak versi Codex ini -> coba tingkat berikutnya; pengerasan inti tetap terpasang.
      argTier += 1;
      inputBuffer = '';
      spawnChild(prompt);
      return;
    }
    if (!ended && !initDone) {
      // Fail-closed: JANGAN ulangi tanpa -c. Tanpa pengerasan, perintah baca-saja "aman" bisa jalan sebelum pemutus bereaksi.
      if (shutdownTimer) clearTimeout(shutdownTimer);
      shutdownTimer = null;
      stopProcessGroup();
      finish('Codex menolak opsi pengerasan (-c features.shell_tool=false) atau gagal start; perbarui Codex CLI di Termux. ' + stderr.trim().slice(-500), false);
      return;
    }
    if (shutdownTimer) clearTimeout(shutdownTimer);
    shutdownTimer = null;
    stopProcessGroup();
    if (ended) return;
    if (inputBuffer.trim()) {
      try { handle(JSON.parse(inputBuffer), prompt); } catch (_) {}
    }
    if (code !== 0) finish(stderr.trim() || ('Codex app-server keluar dengan kode ' + code));
    else if (!answer) finish('Codex tidak mengeluarkan teks jawaban.');
    else finish(null);
  });
  send({
    method: 'initialize',
    id: 1,
    params: {clientInfo: {name: 'mythos_android', title: 'Mythos Android', version: '__MYTHOS_VERSION__'}}
  });
}
let threadExtended = false;
function startThread(extended) {
  threadExtended = extended;
  const params = {cwd: process.cwd(), approvalPolicy: 'untrusted', sandbox: 'read-only'};
  if (extended) params.ephemeral = true; // tidak menyimpan rollout di ~/.codex/sessions
  send({method: 'thread/start', id: 2, params});
}
function handleServerRequest(message) {
  const method = String(message.method || '');
  // Mythos handles its own tool protocol. Never execute Codex-native edits/commands on the model-provider path.
  if (method === 'item/commandExecution/requestApproval' || method === 'item/fileChange/requestApproval') {
    send({id: message.id, result: {decision: 'decline'}});
    return;
  }
  send({id: message.id, error: {code: -32601, message: 'Codex-native tools are disabled in the Mythos model adapter.'}});
}
function handle(message, prompt) {
  if (message.method && message.id !== undefined && message.id !== null) {
    handleServerRequest(message);
    return;
  }
  if (message.id === 1) {
    initDone = true;
    if (message.error) { finish('Inisialisasi Codex app-server gagal: ' + cleanError(message.error)); return; }
    send({method: 'initialized'});
    startThread(true);
    return;
  }
  if (message.id === 2) {
    if (message.error && threadExtended) { startThread(false); return; } // opsi ephemeral ditolak -> ulangi polos
    if (message.error) { finish('Gagal memulai sesi Codex: ' + cleanError(message.error)); return; }
    threadId = message.result && message.result.thread && message.result.thread.id || '';
    if (!threadId) { finish('Codex app-server tidak mengembalikan ID sesi.'); return; }
    send({method: 'turn/start', id: 3, params: {
      threadId,
      cwd: process.cwd(),
      approvalPolicy: 'untrusted',
      input: [{type: 'text', text: retryPrompt || prompt}]
    }});
    return;
  }
  if (message.id === 3 && message.result) {
    const t = message.result.turn || {};
    if (t.id) currentTurnId = String(t.id);
  }
  if (message.id === 3 && message.error) {
    finish('Permintaan Codex ditolak: ' + cleanError(message.error));
    return;
  }
  const params = message.params || {};
  if (params.threadId && deadThreads.has(params.threadId)) return;
  if (params.threadId && threadId && params.threadId !== threadId) return;
  if (params.turnId && !currentTurnId) currentTurnId = String(params.turnId);
  if ((message.method === 'item/started' || message.method === 'item/completed') && params.item && BLOCKED_ITEMS.has(params.item.type)) {
    const blockedType = params.item.type;
    // Tripwire tetap: giliran dihentikan SEGERA agar tool bawaan tidak sempat jalan.
    const deadThread = threadId;
    if (deadThread) {
      deadThreads.add(deadThread);
      send({method: 'turn/interrupt', id: 90 + deadThreads.size, params: {threadId: deadThread, turnId: currentTurnId || params.turnId || ''}});
    }
    // Teks yang sudah lengkap berisi <tool_use> tetap bisa dipakai Mythos.
    if (answer.indexOf('</tool_use>') >= 0) {
      stopProcessGroup();
      finish(null);
      return;
    }
    // patch9: pencarian web bawaan diterjemahkan menjadi WebSearch milik Mythos (kueri dari item), tanpa ulang dan tanpa galat.
    const translated = nativeToMythosCall(params.item);
    if (translated) {
      answer += translated;
      emit({type: 'delta', text: translated});
      stopProcessGroup();
      finish(null);
      return;
    }
    if (nativeRetries < MAX_NATIVE_RETRIES && deadThread) {
      nativeRetries += 1;
      // Kalimat pembuka yang sudah tampil tidak diulang; lanjutkan langsung dengan <tool_use>.
      const already = answer.trim() ? 'Kalimat pembuka sudah tampil ke pengguna; JANGAN mengulanginya, langsung tulis <tool_use>.\n' : '';
      retryPrompt = nativeReminder(nativeRetries, blockedType) + already + prompt;
      purgeThread(deadThread);
      threadId = '';
      currentTurnId = '';
      currentText = '';
      startThread(true);
      return;
    }
    stopProcessGroup();
    finish('Codex mencoba memakai tool bawaan (' + blockedType + ') ' + (nativeRetries + 1) + ' kali; Mythos membatalkannya. Coba kirim ulang atau ganti model.', true);
    return;
  }
  if (message.method === 'item/agentMessage/delta') {
    const delta = params.delta || '';
    if (typeof delta === 'string' && delta.length) {
      answer += delta;
      currentText += delta;
      emit({type: 'delta', text: delta});
    }
    return;
  }
  if (message.method === 'item/completed') {
    const item = params.item || {};
    if (item.type === 'agentMessage' || item.type === 'agent_message') {
      const full = typeof item.text === 'string' ? item.text : '';
      if (full && currentText.length === 0) {
        answer += full;
        emit({type: 'delta', text: full});
      } else if (full && full.startsWith(currentText)) {
        const rest = full.slice(currentText.length);
        answer += rest;
        if (rest) emit({type: 'delta', text: rest});
      }
      currentText = '';
    }
    return;
  }
  if (message.method === 'error') {
    const detail = params.error && params.error.message || params.message;
    if (detail) stderr = String(detail).slice(-8000);
    return;
  }
  if (message.method === 'turn/completed') {
    const turn = params.turn || {};
    const status = turn.status || '';
    if (status && status !== 'completed' && status !== 'complete') {
      finish(cleanError(turn.error) || ('Giliran Codex berakhir dengan status: ' + status));
    } else if (!answer) {
      finish('Codex tidak mengeluarkan teks jawaban.');
    } else {
      finish(null);
    }
  }
}

function validSocketParams() {
  return Number.isInteger(PORT) && PORT >= 1 && PORT <= 65535 && /^[A-Za-z0-9_-]{32,128}$/.test(TOKEN);
}
// optional=true (prompt lewat argumen): kegagalan soket diabaikan, sesi tetap jalan dan hasil tetap sampai lewat stdout.
function connectSocket(optional) {
  try {
    socket = net.connect({host: '127.0.0.1', port: PORT});
  } catch (e) {
    socket = null;
    if (!optional) finish('Tidak dapat tersambung ke aplikasi: ' + e.message);
    return;
  }
  socket.setNoDelay(true);
  socket.on('connect', () => {
    socket.write(TOKEN + '\n');
    sockReady = true;
    const queued = sockQueue; sockQueue = [];
    queued.forEach(l => { try { socket.write(l); } catch (_) {} });
  });
  socket.on('data', chunk => {
    socketBuffer += chunk.toString('utf8');
    let nl;
    while ((nl = socketBuffer.indexOf('\n')) >= 0) {
      const line = socketBuffer.slice(0, nl);
      socketBuffer = socketBuffer.slice(nl + 1);
      if (line.trim() === 'cancel') { stopProcessGroup(); finish('Dibatalkan oleh aplikasi.'); continue; }
      if (optional) continue;
      try { startSession(JSON.parse(line)); }
      catch (e) { finish('Permintaan dari aplikasi tidak valid: ' + e.message); }
    }
  });
  socket.on('error', e => {
    sockReady = false; sockQueue = [];
    if (!optional && !ended) finish('Sambungan langsung ke aplikasi terputus: ' + e.message);
  });
  socket.on('close', () => {
    sockReady = false; sockQueue = [];
    if (optional) return;
    if (!ended && child) {
      if (child.stdin && !child.stdin.destroyed) {
        try { child.stdin.end(); } catch (_) {}
      }
      stopProcessGroup();
    }
  });
}

// Pembatalan cadangan dari aplikasi (pkill saat soket tidak sampai): hentikan Codex dan seluruh process group-nya, lalu keluar.
for (const sig of ['SIGTERM', 'SIGHUP', 'SIGINT']) {
  process.on(sig, () => {
    stopProcessGroup();
    finish('Dibatalkan oleh aplikasi.');
    setTimeout(() => process.exit(process.exitCode || 1), 4000);
  });
}

if (ARGV_PROMPT) {
  let decoded = '';
  try { decoded = Buffer.from(PROMPT_B64, 'base64').toString('utf8'); } catch (_) {}
  if (validSocketParams()) connectSocket(true);
  startSession({prompt: decoded});
} else if (!validSocketParams()) {
  process.stderr.write('Parameter sesi Codex tidak valid.\n');
  process.exit(2);
} else {
  connectSocket(false);
}
