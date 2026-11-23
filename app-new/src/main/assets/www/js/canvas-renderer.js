/**
 * canvas-renderer.js
 * Renderer kartu simulasi berbasis HTML5 <canvas> dengan tata letak proporsional,
 * perataan titik dua (:), huruf sans-serif (Inter) untuk kolom data, dan monospace untuk NIK.
 */

const CARD_CANVAS_CONFIG = {
    width: 1011,
    height: 638,
    photo: {
        x: 723,
        y: 126,
        width: 238,
        height: 282,
        radius: 8
    },
    signature: {
        x: 732,
        y: 488,
        width: 220,
        height: 96
    },
    text: {
        provinsi: { x: 505.5, y: 48, align: "center", font: "700 33px Inter, sans-serif", maxWidth: 920 },
        kabupaten: { x: 505.5, y: 82, align: "center", font: "600 30px Inter, sans-serif", maxWidth: 920 },
        nik: { x: 257, y: 135, align: "left", font: "700 36px 'JetBrains Mono', monospace", maxWidth: 435, letterSpacing: "4.5px" },
        nama: { x: 272, y: 184, align: "left", font: "600 22px Inter, sans-serif", maxWidth: 420 },
        ttl: { x: 272, y: 215, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        jenisKelamin: { x: 272, y: 244, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 215 },
        golDarah: { x: 676, y: 244, align: "left", font: "700 22px Inter, sans-serif", maxWidth: 35 },
        alamat: { x: 272, y: 273, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        rtRw: { x: 272, y: 302, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        kelurahan: { x: 272, y: 332, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        kecamatan: { x: 272, y: 360, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        agama: { x: 272, y: 389, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        statusPerkawinan: { x: 272, y: 418, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        pekerjaan: { x: 272, y: 446, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        kewarganegaraan: { x: 272, y: 476, align: "left", font: "500 22px Inter, sans-serif", maxWidth: 420 },
        berlaku: { x: 272, y: 505, align: "left", font: "700 22px Inter, sans-serif", maxWidth: 420 },
        kotaTerbit: { x: 842, y: 453, align: "center", font: "600 21px Inter, sans-serif", maxWidth: 260 },
        tanggalTerbit: { x: 842, y: 478, align: "center", font: "600 20px Inter, sans-serif", maxWidth: 260 }
    }
};

let cachedDefaultTemplate = null;
let cachedPhotoSrc = "";
let cachedPhotoImg = null;
let cachedSigSrc = "";
let cachedSigImg = null;

function drawRoundedRectPath(ctx, x, y, w, h, r) {
    const radius = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + radius, y);
    ctx.lineTo(x + w - radius, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + radius);
    ctx.lineTo(x + w, y + h - radius);
    ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h);
    ctx.lineTo(x + radius, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - radius);
    ctx.lineTo(x, y + radius);
    ctx.quadraticCurveTo(x, y, x + radius, y);
    ctx.closePath();
}

function createDefaultTemplateCanvas() {
    if (cachedDefaultTemplate) return cachedDefaultTemplate;

    const { width, height, photo } = CARD_CANVAS_CONFIG;
    const tpl = document.createElement("canvas");
    tpl.width = width;
    tpl.height = height;
    const ctx = tpl.getContext("2d");

    // Latar belakang gradien radial biru tua simulasi
    const grad = ctx.createRadialGradient(width * 0.15, height * 0.15, 20, width * 0.45, height * 0.5, width * 0.9);
    grad.addColorStop(0, "#1d4ed8");
    grad.addColorStop(0.52, "#1e3a8a");
    grad.addColorStop(1, "#0f172a");
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, width, height);

    // Pola titik halus
    ctx.fillStyle = "rgba(255, 255, 255, 0.11)";
    for (let px = 14; px < width; px += 28) {
        for (let py = 14; py < height; py += 28) {
            ctx.beginPath();
            ctx.arc(px, py, 1.5, 0, Math.PI * 2);
            ctx.fill();
        }
    }

    // Watermark diagonal SIMULASI FORMULIR
    ctx.save();
    ctx.translate(width / 2, height / 2);
    ctx.rotate(-0.21);
    ctx.font = "800 64px Inter, sans-serif";
    ctx.fillStyle = "rgba(255, 255, 255, 0.07)";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText("SIMULASI FORMULIR", 0, 0);
    ctx.restore();

    // Label kolom statis dengan titik dua (:) rata di x = 250
    const labels = [
        { text: "NIK", x: 40, y: 135, colonX: 214, font: "700 32px 'JetBrains Mono', monospace", color: "#ffffff" },
        { text: "Nama", x: 40, y: 184, colonX: 260 },
        { text: "Tempat/Tgl Lahir", x: 40, y: 215, colonX: 260 },
        { text: "Jenis Kelamin", x: 40, y: 244, colonX: 260 },
        { text: "Alamat", x: 40, y: 273, colonX: 260 },
        { text: "RT/RW", x: 88, y: 302, colonX: 260, sub: true },
        { text: "Kel/Desa", x: 88, y: 332, colonX: 260, sub: true },
        { text: "Kecamatan", x: 88, y: 360, colonX: 260, sub: true },
        { text: "Agama", x: 40, y: 389, colonX: 260 },
        { text: "Status Perkawinan", x: 40, y: 418, colonX: 260 },
        { text: "Pekerjaan", x: 40, y: 446, colonX: 260 },
        { text: "Kewarganegaraan", x: 40, y: 476, colonX: 260 },
        { text: "Berlaku Hingga", x: 40, y: 505, colonX: 260 }
    ];

    ctx.textAlign = "left";
    ctx.textBaseline = "alphabetic";
    labels.forEach((item) => {
        ctx.font = item.font || "500 22px Inter, sans-serif";
        ctx.fillStyle = item.color || (item.sub ? "rgba(186, 230, 253, 0.82)" : "rgba(186, 230, 253, 0.94)");
        ctx.fillText(item.text, item.x, item.y);
        ctx.fillText(":", item.colonX, item.y);
    });

    // Label Gol. Darah sejajar di baris Jenis Kelamin
    ctx.font = "500 22px Inter, sans-serif";
    ctx.fillStyle = "rgba(186, 230, 253, 0.94)";
    ctx.fillText("Gol. Darah :", 544, 244);

    // Bingkai Pas Foto (rasio 3:4)
    ctx.save();
    drawRoundedRectPath(ctx, photo.x, photo.y, photo.width, photo.height, photo.radius);
    ctx.fillStyle = "rgba(8, 47, 73, 0.55)";
    ctx.fill();
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = "rgba(125, 211, 252, 0.45)";
    ctx.stroke();

    // Siluet placeholder pas foto
    const cx = photo.x + photo.width / 2;
    const cy = photo.y + photo.height / 2;
    ctx.fillStyle = "rgba(186, 230, 253, 0.42)";
    ctx.beginPath();
    ctx.arc(cx, cy - 28, 30, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx, cy + 58, 50, Math.PI, 0);
    ctx.fill();
    ctx.restore();

    cachedDefaultTemplate = tpl;
    return tpl;
}

function getTextPalette(theme) {
    if (theme === "dark") {
        return {
            header: "#000000",
            subheader: "#000000",
            nik: "#000000",
            value: "#0a0a0a",
            accent: "#0a0a0a"
        };
    }
    return {
        header: "#ffffff",
        subheader: "#bae6fd",
        nik: "#fcd34d",
        value: "#f8fafc",
        accent: "#fcd34d"
    };
}

function formatFontString(fontStr, scale, weightOverride) {
    let out = fontStr;
    if (weightOverride) {
        out = out.replace(/^\d+\s+/, `${weightOverride} `);
    }
    if (scale && scale !== 1) {
        out = out.replace(/(\d+(?:\.\d+)?)px/, (_, n) => `${(parseFloat(n) * scale).toFixed(1)}px`);
    }
    return out;
}

function drawTruncatedText(ctx, text, spec, color, cal = { offsetX: 0, offsetY: 0, fontScale: 1, fontWeight: 0, blurPx: 0, opacity: 1 }) {
    const safeText = String(text || "-").toUpperCase();
    ctx.save();
    ctx.font = formatFontString(spec.font, cal.fontScale, cal.fontWeight);
    if (cal.opacity !== undefined && cal.opacity < 1) {
        ctx.globalAlpha = cal.opacity;
    }
    if (cal.blurPx && cal.blurPx > 0 && "filter" in ctx) {
        ctx.filter = `blur(${cal.blurPx}px)`;
    }
    ctx.fillStyle = color;
    ctx.textAlign = spec.align || "left";
    ctx.textBaseline = "alphabetic";
    if (spec.letterSpacing && "letterSpacing" in ctx) {
        ctx.letterSpacing = spec.letterSpacing;
    }

    let output = safeText;
    if (spec.maxWidth && ctx.measureText(output).width > spec.maxWidth) {
        while (output.length > 1 && ctx.measureText(output + "…").width > spec.maxWidth) {
            output = output.slice(0, -1);
        }
        output += "…";
    }
    ctx.fillText(output, spec.x + (cal.offsetX || 0), spec.y + (cal.offsetY || 0));
    ctx.restore();
}

function drawCoverPhoto(ctx, img, box) {
    const imgRatio = img.width / img.height;
    const boxRatio = box.width / box.height;
    let sx = 0, sy = 0, sw = img.width, sh = img.height;

    if (imgRatio > boxRatio) {
        sw = img.height * boxRatio;
        sx = (img.width - sw) / 2;
    } else {
        sh = img.width / boxRatio;
        sy = (img.height - sh) / 2;
    }

    ctx.save();
    drawRoundedRectPath(ctx, box.x, box.y, box.width, box.height, box.radius);
    ctx.clip();
    ctx.drawImage(img, sx, sy, sw, sh, box.x, box.y, box.width, box.height);
    ctx.lineWidth = 2.5;
    ctx.strokeStyle = "rgba(125, 211, 252, 0.55)";
    ctx.stroke();
    ctx.restore();
}

function drawCardContents(ctx, data, templateSource, photoImg, sigImg, textTheme, cal) {
    const { width, height, text, photo, signature } = CARD_CANVAS_CONFIG;
    const palette = getTextPalette(textTheme);
    ctx.clearRect(0, 0, width, height);

    // 1. Muat gambar template latar ke <canvas>
    ctx.drawImage(templateSource, 0, 0, width, height);

    // 2. Tulis setiap kolom teks sesuai titik koordinat (x, y)
    drawTruncatedText(ctx, data.provinsiLabel, text.provinsi, palette.header, { ...cal, offsetX: 0 });
    drawTruncatedText(ctx, data.kabupatenLabel, text.kabupaten, palette.subheader, cal);
    drawTruncatedText(ctx, data.nik, text.nik, palette.nik, cal);
    drawTruncatedText(ctx, data.nama, text.nama, palette.value, cal);
    drawTruncatedText(ctx, data.ttl, text.ttl, palette.value, cal);
    drawTruncatedText(ctx, data.jenisKelamin, text.jenisKelamin, palette.value, cal);
    drawTruncatedText(ctx, data.golDarah, text.golDarah, palette.value, cal);
    drawTruncatedText(ctx, data.alamat, text.alamat, palette.value, cal);
    drawTruncatedText(ctx, data.rtRw, text.rtRw, palette.value, cal);
    drawTruncatedText(ctx, data.kelurahan, text.kelurahan, palette.value, cal);
    drawTruncatedText(ctx, data.kecamatan, text.kecamatan, palette.value, cal);
    drawTruncatedText(ctx, data.agama, text.agama, palette.value, cal);
    drawTruncatedText(ctx, data.statusPerkawinan, text.statusPerkawinan, palette.value, cal);
    drawTruncatedText(ctx, data.pekerjaan, text.pekerjaan, palette.value, cal);
    drawTruncatedText(ctx, data.kewarganegaraan, text.kewarganegaraan, palette.value, cal);
    drawTruncatedText(ctx, "SEUMUR HIDUP", text.berlaku, palette.accent, cal);
    drawTruncatedText(ctx, data.kotaTerbit || "-", text.kotaTerbit, palette.value, cal);
    drawTruncatedText(ctx, data.tanggalTerbit || "-", text.tanggalTerbit, palette.value, cal);

    // 3. Tempelkan pas foto di bingkai foto (xFoto, yFoto, lebarFoto, tinggiFoto)
    if (photoImg && photoImg.complete && photoImg.naturalWidth > 0) {
        drawCoverPhoto(ctx, photoImg, photo);
    }

    // 4. Tempelkan tanda tangan di bawah kota & tanggal pembuatan (otomatis putih di latar gelap, hitam di latar terang)
    if (sigImg && sigImg.complete && sigImg.naturalWidth > 0) {
        if (textTheme === "light") {
            const tint = document.createElement("canvas");
            tint.width = sigImg.naturalWidth || signature.width;
            tint.height = sigImg.naturalHeight || signature.height;
            const tctx = tint.getContext("2d");
            tctx.drawImage(sigImg, 0, 0, tint.width, tint.height);
            tctx.globalCompositeOperation = "source-in";
            tctx.fillStyle = "#ffffff";
            tctx.fillRect(0, 0, tint.width, tint.height);
            ctx.drawImage(tint, signature.x, signature.y, signature.width, signature.height);
        } else {
            ctx.drawImage(sigImg, signature.x, signature.y, signature.width, signature.height);
        }
    }
}

function renderKtpCanvas(canvasEl, data, customTemplateImg = null, textTheme = "light", cal = { offsetX: 0, offsetY: 0, fontScale: 1, fontWeight: 0, blurPx: 0, opacity: 1 }) {
    if (!canvasEl) return;
    const ctx = canvasEl.getContext("2d");
    const hasCustom = customTemplateImg && customTemplateImg.complete && customTemplateImg.naturalWidth > 0;
    const templateSource = hasCustom ? customTemplateImg : createDefaultTemplateCanvas();

    const photoSrc = data.photoDataUrl || "";
    const sigSrc = data.signatureDataUrl || "";

    const renderNow = () => {
        const pImg = (photoSrc && cachedPhotoSrc === photoSrc && cachedPhotoImg && cachedPhotoImg.complete) ? cachedPhotoImg : null;
        const sImg = (sigSrc && cachedSigSrc === sigSrc && cachedSigImg && cachedSigImg.complete) ? cachedSigImg : null;
        drawCardContents(ctx, data, templateSource, pImg, sImg, textTheme, cal);
    };

    let waiting = false;
    if (!photoSrc) {
        cachedPhotoSrc = "";
        cachedPhotoImg = null;
    } else if (cachedPhotoSrc !== photoSrc || !cachedPhotoImg || !cachedPhotoImg.complete) {
        waiting = true;
        const p = new Image();
        p.onload = () => {
            cachedPhotoSrc = photoSrc;
            cachedPhotoImg = p;
            renderNow();
        };
        p.src = photoSrc;
    }

    if (!sigSrc) {
        cachedSigSrc = "";
        cachedSigImg = null;
    } else if (cachedSigSrc !== sigSrc || !cachedSigImg || !cachedSigImg.complete) {
        waiting = true;
        const s = new Image();
        s.onload = () => {
            cachedSigSrc = sigSrc;
            cachedSigImg = s;
            renderNow();
        };
        s.src = sigSrc;
    }

    if (!waiting) {
        renderNow();
    }
}

function exportCanvasToPngDataUrl(canvasEl) {
    return canvasEl.toDataURL("image/png");
}
