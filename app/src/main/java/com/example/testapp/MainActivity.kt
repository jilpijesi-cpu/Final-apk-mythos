package com.example.testapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this).apply {
            text = "Halo BOS, Tes Berhasil!"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }
        setContentView(textView)
    }
}
