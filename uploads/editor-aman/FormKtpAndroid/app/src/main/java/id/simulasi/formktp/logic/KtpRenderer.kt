package id.simulasi.formktp.logic

import android.content.Context
import android.graphics.*
import id.simulasi.formktp.R
import id.simulasi.formktp.model.KtpData

object KtpRenderer {
    private const val WIDTH = 1011
    private const val HEIGHT = 638

    fun render(context: Context, data: KtpData): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        drawBackground(context, canvas, paint)
        drawLabels(canvas, paint)
        drawValues(canvas, data, paint)
        drawPhoto(canvas, data.foto, paint)
        drawSignature(canvas, data.tandaTangan, paint)

        return bitmap
    }

    private fun drawBackground(context: Context, canvas: Canvas, paint: Paint) {
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bgBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.bg_default, options)
        if (bgBitmap != null) {
            canvas.drawBitmap(bgBitmap, null, Rect(0, 0, WIDTH, HEIGHT), paint)
            bgBitmap.recycle()
            return
        }

        // Fallback gradient background
        val gradient = RadialGradient(
            WIDTH * 0.15f, HEIGHT * 0.15f, WIDTH * 0.9f,
            intArrayOf(Color.parseColor("#1d4ed8"), Color.parseColor("#1e3a8a"), Color.parseColor("#0f172a")),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null

        // Dot pattern
        paint.color = Color.parseColor("#1CFFFFFF") // ~11% opacity
        for (px in 14 until WIDTH step 28) {
            for (py in 14 until HEIGHT step 28) {
                canvas.drawCircle(px.toFloat(), py.toFloat(), 1.5f, paint)
            }
        }

        // Watermark
        paint.color = Color.parseColor("#12FFFFFF") // ~7% opacity
        paint.textSize = 64f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.save()
        canvas.translate(WIDTH / 2f, HEIGHT / 2f)
        canvas.rotate(-12f) // ~0.21 rad
        canvas.drawText("SIMULASI FORMULIR", 0f, 0f, paint)
        canvas.restore()
    }

    private fun drawLabels(canvas: Canvas, paint: Paint) {
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 22f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val labelColor = Color.parseColor("#EBF8FF") // subheader color approx

        val labels = listOf(
            Triple("NIK", 40f, 135f),
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

        labels.forEach { (text, x, y) ->
            if (text == "NIK") {
                paint.textSize = 32f
                paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                paint.color = Color.WHITE
            } else {
                paint.textSize = 22f
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                paint.color = labelColor
            }
            canvas.drawText(text, x, y, paint)
            
            // Colon
            val colonX = if (text == "NIK") 214f else 260f
            canvas.drawText(":", colonX, y, paint)
        }

        // Gol Darah label
        paint.textSize = 22f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.color = labelColor
        canvas.drawText("Gol. Darah :", 544f, 244f, paint)
    }

    private fun drawValues(canvas: Canvas, data: KtpData, paint: Paint) {
        paint.textAlign = Paint.Align.LEFT
        val valueColor = Color.parseColor("#F8FAFC")
        val accentColor = Color.parseColor("#FCD34D")

        // Header
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.WHITE
        paint.textSize = 33f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText(data.provinsi.uppercase(), WIDTH / 2f, 48f, paint)
        paint.textSize = 30f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText(data.kabupaten.uppercase(), WIDTH / 2f, 82f, paint)

        paint.textAlign = Paint.Align.LEFT
        // NIK
        paint.color = accentColor
        paint.textSize = 36f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(data.nik, 257f, 135f, paint)

        // Other values
        paint.color = valueColor
        paint.textSize = 22f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        
        val values = listOf(
            data.nama to 184f,
            "${data.tempatLahir}, ${data.tanggalLahir}" to 215f,
            data.jenisKelamin to 244f,
            data.alamat to 273f,
            data.rtRw to 302f,
            data.kelDesa to 332f,
            data.kecamatan to 360f,
            data.agama to 389f,
            data.statusPerkawinan to 418f,
            data.pekerjaan to 446f,
            data.kewarganegaraan to 476f
        )

        values.forEach { (text, y) ->
            canvas.drawText(text.uppercase(), 272f, y, paint)
        }

        // Gol Darah value
        paint.color = valueColor
        canvas.drawText(data.golDarah.uppercase(), 676f, 244f, paint)

        // Berlaku Hingga
        paint.color = accentColor
        canvas.drawText(data.berlakuHingga.uppercase(), 272f, 505f, paint)

        // Kota & Tanggal Terbit
        paint.textAlign = Paint.Align.CENTER
        paint.color = valueColor
        paint.textSize = 21f
        canvas.drawText(data.kotaTerbit.uppercase(), 842f, 453f, paint)
        paint.textSize = 20f
        canvas.drawText(data.tanggalTerbit.uppercase(), 842f, 478f, paint)
    }

    private fun drawPhoto(canvas: Canvas, photo: Bitmap?, paint: Paint) {
        val x = 723f
        val y = 126f
        val w = 238f
        val h = 282f
        val r = 8f

        // Frame
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#8C082F49") // ~55% opacity
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, r, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.parseColor("#737DD3FC") // ~45% opacity
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL

        if (photo != null) {
            canvas.save()
            val path = Path()
            path.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.clipPath(path)
            
            // Center crop logic
            val photoRatio = photo.width.toFloat() / photo.height
            val boxRatio = w / h
            var srcRect: Rect
            if (photoRatio > boxRatio) {
                val sw = photo.height * boxRatio
                val sx = (photo.width - sw) / 2
                srcRect = Rect(sx.toInt(), 0, (sx + sw).toInt(), photo.height)
            } else {
                val sh = photo.width / boxRatio
                val sy = (photo.height - sh) / 2
                srcRect = Rect(0, sy.toInt(), photo.width, (sy + sh).toInt())
            }
            canvas.drawBitmap(photo, srcRect, rect, paint)
            canvas.restore()
        } else {
            // Placeholder silhouette
            paint.color = Color.parseColor("#6BBEE6FD") // ~42% opacity
            canvas.drawCircle(x + w / 2f, y + h / 2f - 28f, 30f, paint)
            val bodyRect = RectF(x + w / 2f - 50f, y + h / 2f + 8f, x + w / 2f + 50f, y + h / 2f + 108f)
            canvas.drawArc(bodyRect, 180f, 180f, true, paint)
        }
    }

    private fun drawSignature(canvas: Canvas, sig: Bitmap?, paint: Paint) {
        if (sig == null) return
        val x = 732f
        val y = 488f
        val w = 220f
        val h = 96f
        
        // Draw signature (assuming it's black on transparent/white, we might need to tint it white)
        val rect = RectF(x, y, x + w, y + h)
        
        // Simple tinting to white if needed (like in JS)
        val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        tintPaint.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(sig, null, rect, tintPaint)
    }
}
