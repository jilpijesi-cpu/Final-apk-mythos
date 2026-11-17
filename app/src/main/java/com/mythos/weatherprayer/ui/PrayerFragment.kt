package com.mythos.weatherprayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.mythos.weatherprayer.R
import com.mythos.weatherprayer.databinding.FragmentPrayerBinding

class PrayerFragment : Fragment() {
    private var _binding: FragmentPrayerBinding? = null
    private val binding get() = _binding!!

    private val prayers = listOf(
        Pair("Imsak", "04:15"),
        Pair("Subuh", "04:25"),
        Pair("Terbit", "05:40"),
        Pair("Dzuhur", "11:55"),
        Pair("Ashar", "15:15"),
        Pair("Maghrib", "17:50"),
        Pair("Isya'", "19:05")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPrayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupPrayerTimes()
    }

    private fun setupPrayerTimes() {
        binding.prayerTimesContainer.removeAllViews()
        for (prayer in prayers) {
            val itemView = layoutInflater.inflate(R.layout.item_prayer_time, binding.prayerTimesContainer, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvPrayerName)
            val tvTime = itemView.findViewById<TextView>(R.id.tvPrayerTime)
            tvName.text = prayer.first
            tvTime.text = prayer.second
            binding.prayerTimesContainer.addView(itemView)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
