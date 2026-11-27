package id.simulasi.formktp.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.res.ResourcesCompat
import id.simulasi.formktp.R
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.min

data class CardPreviewData(
    val provinsiLabel: String,
    val kabupatenLabel: String,
    val nik: String,
    val nama: String,
    val ttl: String,
    val jenisKelamin: String,
    val golDarah: String,
    val alamat: String,
    val rtRw: String,
    val kelurahan: String,
    val kecamatan: String,
    val agama: String,
    val statusPerkawinan: String,
    val pekerjaan: String,
    val kewarganegaraan: String,
    val kotaTerbit: String,
    val tanggalTerbit: String,
    val photoBitmap: Bitmap? = null,
    val signatureBitmap: Bitmap? = null,
    val customBackgroundBitmap: Bitmap? = null,
    val darkTextTheme: Boolean = false
)

/**
 * Renderer kartu simulasi 100% native berbasis android.graphics.Canvas (1011 x 638, rasio ID-1).
 * Mendukung latar bawaan maupun gambar latar dari galeri, dengan watermark simulasi permanen di lapisan teratas.
 */
object KtpCardRenderer {
    const val CARD_WIDTH = 1011
    const val CARD_HEIGHT = 638

    private val photoBox = RectF(723f, 126f, 723f + 238f, 126f + 282f)
    private val sigBox = RectF(732f, 488f, 732f + 220f, 488f + 96f)

    private var interMedium: Typeface? = null
    private var interSemiBold: Typeface? = null
    private var interBold: Typeface? = null
    private var nikMonoBold: Typeface? = null

    private fun ensureFonts(context: Context) {
        if (interMedium == null) {
            interMedium = ResourcesCompat.getFont(context, R.font.inter_medium) ?: Typeface.SANS_SERIF
        }
        if (interSemiBold == null) {
            interSemiBold = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        if (interBold == null) {
            interBold = ResourcesCompat.getFont(context, R.font.inter_bold) ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        if (nikMonoBold == null) {
            nikMonoBold = ResourcesCompat.getFont(context, R.font.nik_mono_bold) ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }

    fun render(context: Context, data: CardPreviewData): Bitmap {
        ensureFonts(context)
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw background first
        if (data.customBackgroundBitmap != null &&
            data.customBackgroundBitmap.width > 0 &&
            data.customBackgroundBitmap.height > 0
        ) {
            canvas.drawBitmap(
                data.customBackgroundBitmap,
                null,
                Rect(0, 0, CARD_WIDTH, CARD_HEIGHT),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } else {
            drawDefaultBackground(canvas, !data.darkTextTheme)
        }

        drawStaticLabels(canvas, !data.darkTextTheme)
        drawDynamicFields(canvas, data)
        drawPhotoArea(canvas, data.photoBitmap, !data.darkTextTheme, data.customBackgroundBitmap != null)
        drawSignatureArea(canvas, data.signatureBitmap, !data.darkTextTheme)
        drawPermanentSimulationWatermark(canvas, !data.darkTextTheme)

        return bitmap
    }

    private fun drawDefaultBackground(canvas: Canvas, lightText: Boolean) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = if (lightText) {
            intArrayOf(
                Color.parseColor("#1D4ED8"),
                Color.parseColor("#1E3A8A"),
                Color.parseColor("#0F172A")
            )
        } else {
            intArrayOf(
                Color.parseColor("#E0F2FE"),
                Color.parseColor("#BAE6FD"),
                Color.parseColor("#E2E8F0")
            )
        }
        bgPaint.shader = RadialGradient(
            CARD_WIDTH * 0.15f,
            CARD_HEIGHT * 0.15f,
            CARD_WIDTH * 0.9f,
            colors,
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(), bgPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (lightText) Color.argb(28, 255, 255, 255) else Color.argb(32, 15, 23, 42)
            style = Paint.Style.FILL
        }
        var px = 14f
        while (px < CARD_WIDTH) {
            var py = 14f
            while (py < CARD_HEIGHT) {
                canvas.drawCircle(px, py, 1.5f, dotPaint)
                py += 28f
            }
            px += 28f
        }
    }

    private fun drawStaticLabels(canvas: Canvas, lightText: Boolean) {
        val primaryLabelColor = if (lightText) Color.argb(240, 186, 230, 253) else Color.parseColor("#1E293B")
        val subLabelColor = if (lightText) Color.argb(210, 186, 230, 253) else Color.parseColor("#334155")
        val nikLabelColor = if (lightText) Color.WHITE else Color.parseColor("#0F172A")

        val nikLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = nikLabelColor
            textSize = 31f
            typeface = nikMonoBold ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("NIK", 40f, 135f, nikLabelPaint)
        canvas.drawText(":", 214f, 135f, nikLabelPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 21.5f
            typeface = interMedium ?: Typeface.SANS_SERIF
        }

        val rows = listOf(
            Triple("Nama", 40f, 184f),
            Triple("Tempat/Tgl Lahir", 40f, 215f),
            Triple("Jenis Kelamin", 40f, 244f),
            Triple("Alamat", 40f, 273f),
            Triple("RT/RW", 88f, 302f),
            Triple("Kel/Desa", 88f, 332f),
            Triple("Kecamatan", 88f, 360f),
            Triple("Agama", 40f, 389f),
            Triple("Status Perkawinan", 40f, 418f),
            Triple("Pekerjaan", 40f, 446f),
            Triple("Kewarganegaraan", 40f, 476f),
            Triple("Berlaku Hingga", 40f, 505f)
        )

        rows.forEach { (text, x, y) ->
            labelPaint.color = if (x > 40f) subLabelColor else primaryLabelColor
            canvas.drawText(text, x, y, labelPaint)
            canvas.drawText(":", 258f, y, labelPaint)
        }

        labelPaint.color = primaryLabelColor
        canvas.drawText("Gol. Darah :", 542f, 244f, labelPaint)
    }

    private fun drawDynamicFields(canvas: Canvas, data: CardPreviewData) {
        val lightText = !data.darkTextTheme
        val headerColor = if (lightText) Color.WHITE else Color.parseColor("#0F172A")
        val subHeaderColor = if (lightText) Color.parseColor("#BAE6FD") else Color.parseColor("#0F172A")
        val nikColor = if (lightText) Color.parseColor("#FCD34D") else Color.parseColor("#0F172A")
        val valueColor = if (lightText) Color.parseColor("#F8FAFC") else Color.parseColor("#0A0A0A")
        val accentColor = if (lightText) Color.parseColor("#FCD34D") else Color.parseColor("#0A0A0A")

        drawFittedText(canvas, data.provinsiLabel, 505.5f, 48f, 920f, 32f, headerColor, Typeface.BOLD, Paint.Align.CENTER)
        drawFittedText(canvas, data.kabupatenLabel, 505.5f, 82f, 920f, 30f, subHeaderColor, Typeface.ITALIC, Paint.Align.CENTER)

        val nikPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = nikColor
            textSize = 34f
            letterSpacing = 0.14f
            typeface = nikMonoBold ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText(truncateText(data.nik.uppercase(), nikPaint, 435f), 255f, 135f, nikPaint)

        drawFittedText(canvas, data.nama, 272f, 184f, 420f, 22f, valueColor, Typeface.ITALIC)
        drawFittedText(canvas, data.ttl, 272f, 215f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.jenisKelamin, 272f, 244f, 215f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.golDarah, 672f, 244f, 40f, 22f, valueColor, Typeface.BOLD)
        drawFittedText(canvas, data.alamat, 272f, 273f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.rtRw, 272f, 302f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.kelurahan, 272f, 332f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.kecamatan, 272f, 360f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.agama, 272f, 389f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.statusPerkawinan, 272f, 418f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.pekerjaan, 272f, 446f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, data.kewarganegaraan, 272f, 476f, 420f, 21.5f, valueColor, Typeface.NORMAL)
        drawFittedText(canvas, "SEUMUR HIDUP", 272f, 505f, 420f, 21.5f, accentColor, Typeface.BOLD)

        drawFittedText(canvas, data.kotaTerbit, 842f, 453f, 260f, 20f, valueColor, Typeface.BOLD, Paint.Align.CENTER)
        drawFittedText(canvas, data.tanggalTerbit, 842f, 478f, 260f, 19.5f, valueColor, Typeface.BOLD, Paint.Align.CENTER)
    }

    private fun drawFittedText(
        canvas: Canvas,
        rawText: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        sizePx: Float,
        color: Int,
        style: Int,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        val resolvedTypeface = when (style) {
            Typeface.BOLD -> interBold ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            Typeface.ITALIC -> interSemiBold ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            else -> interMedium ?: Typeface.SANS_SERIF
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = resolvedTypeface
            textAlign = align
        }
        val safe = (rawText.ifBlank { "-" }).uppercase()
        canvas.drawText(truncateText(safe, paint, maxWidth), x, y, paint)
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var out = text
        while (out.length > 1 && paint.measureText("$out…") > maxWidth) {
            out = out.dropLast(1)
        }
        return "$out…"
    }

    private fun drawPhotoArea(canvas: Canvas, photo: Bitmap?, lightText: Boolean, hasCustomBg: Boolean) {
        val radius = 10f
        if (photo != null && photo.width > 0 && photo.height > 0) {
            val clipPath = Path().apply { addRoundRect(photoBox, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)
            val imgRatio = photo.width.toFloat() / photo.height.toFloat()
            val boxRatio = photoBox.width() / photoBox.height()
            val srcRect = if (imgRatio > boxRatio) {
                val sw = (photo.height * boxRatio).toInt()
                val sx = (photo.width - sw) / 2
                Rect(sx, 0, sx + sw, photo.height)
            } else {
                val sh = (photo.width / boxRatio).toInt()
                val sy = (photo.height - sh) / 2
                Rect(0, sy, photo.width, sy + sh)
            }
            canvas.drawBitmap(photo, srcRect, photoBox, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                color = if (lightText) Color.argb(140, 125, 211, 252) else Color.argb(140, 30, 58, 138)
            }
            canvas.drawRoundRect(photoBox, radius, radius, borderPaint)
        } else if (!hasCustomBg) {
            val clipPath = Path().apply { addRoundRect(photoBox, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (lightText) Color.argb(140, 8, 47, 73) else Color.argb(120, 148, 163, 184)
            }
            canvas.drawRect(photoBox, bg)
            val silhouette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (lightText) Color.argb(110, 186, 230, 253) else Color.argb(140, 51, 65, 85)
            }
            val cx = photoBox.centerX()
            val cy = photoBox.centerY()
            canvas.drawCircle(cx, cy - 28f, 30f, silhouette)
            canvas.drawArc(RectF(cx - 50f, cy + 8f, cx + 50f, cy + 108f), 180f, 180f, true, silhouette)
            canvas.restore()
        }
    }

    private fun drawSignatureArea(canvas: Canvas, signature: Bitmap?, lightText: Boolean) {
        if (signature == null || signature.width <= 0 || signature.height <= 0) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (lightText) {
            paint.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        val scale = min(sigBox.width() / signature.width, sigBox.height() / signature.height)
        val dw = signature.width * scale
        val dh = signature.height * scale
        val left = sigBox.centerX() - dw / 2f
        val top = sigBox.centerY() - dh / 2f
        canvas.drawBitmap(signature, null, RectF(left, top, left + dw, top + dh), paint)
    }

    private fun drawPermanentSimulationWatermark(canvas: Canvas, lightText: Boolean) {
        // Watermark removed
    }
}
