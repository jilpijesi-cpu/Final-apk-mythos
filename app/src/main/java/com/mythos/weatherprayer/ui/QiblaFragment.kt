package com.mythos.weatherprayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.mythos.weatherprayer.databinding.FragmentQiblaBinding

class QiblaFragment : Fragment() {
    private var _binding: FragmentQiblaBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQiblaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.ivCompass.rotation = 295f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
