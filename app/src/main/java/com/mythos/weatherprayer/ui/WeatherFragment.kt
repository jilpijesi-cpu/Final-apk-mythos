package com.mythos.weatherprayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mythos.weatherprayer.databinding.FragmentWeatherBinding

class WeatherFragment : Fragment() {
    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWeatherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRefreshWeather.setOnClickListener {
            Toast.makeText(requireContext(), "Cuaca diperbarui: 31°C, Cerah", Toast.LENGTH_SHORT).show()
            binding.tvTemp.text = "31°C"
            binding.tvCondition.text = "Cerah"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
