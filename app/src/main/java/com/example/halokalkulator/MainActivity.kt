package com.example.halokalkulator

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etNum1 = findViewById<EditText>(R.id.etNum1)
        val etNum2 = findViewById<EditText>(R.id.etNum2)
        val btnCalculate = findViewById<Button>(R.id.btnCalculate)
        val tvResult = findViewById<TextView>(R.id.tvResult)

        btnCalculate.setOnClickListener {
            val str1 = etNum1.text.toString()
            val str2 = etNum2.text.toString()
            if (str1.isNotEmpty() && str2.isNotEmpty()) {
                val num1 = str1.toDoubleOrNull() ?: 0.0
                val num2 = str2.toDoubleOrNull() ?: 0.0
                val sum = num1 + num2
                tvResult.text = "Hasil: $sum"
            } else {
                tvResult.text = "Masukkan kedua angka!"
            }
        }
    }
}
