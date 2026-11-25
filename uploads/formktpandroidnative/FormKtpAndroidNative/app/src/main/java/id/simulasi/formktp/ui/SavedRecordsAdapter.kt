package id.simulasi.formktp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import id.simulasi.formktp.databinding.ItemSavedRecordBinding
import id.simulasi.formktp.model.KtpRecord
import id.simulasi.formktp.util.NikParser

class SavedRecordsAdapter(
    private val onEdit: (KtpRecord) -> Unit,
    private val onDelete: (KtpRecord) -> Unit
) : RecyclerView.Adapter<SavedRecordsAdapter.Holder>() {

    private val items = mutableListOf<KtpRecord>()

    fun submitList(newItems: List<KtpRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSavedRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val b: ItemSavedRecordBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(rec: KtpRecord) {
            b.tvItemNik.text = rec.nik
            b.tvItemNama.text = rec.nama
            b.tvItemWilayah.text = "${rec.kabupaten}, ${rec.provinsi}"
            b.tvItemMeta.text = "${rec.jenisKelamin} • ${NikParser.formatIndoDate(rec.tanggalLahir)}"
            b.tvItemWarga.text = rec.kewarganegaraan
            b.btnItemEdit.setOnClickListener { onEdit(rec) }
            b.btnItemDelete.setOnClickListener { onDelete(rec) }
        }
    }
}
