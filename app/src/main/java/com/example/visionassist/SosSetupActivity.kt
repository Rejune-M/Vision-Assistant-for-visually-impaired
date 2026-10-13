package com.example.visionassist

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.visionassist.prefs.SosPrefs

class SosSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sos_setup)

        val prefs = SosPrefs(this)
        val edt = findViewById<EditText>(R.id.edtSos)
        val btn = findViewById<Button>(R.id.btnSaveSos)

        edt.setText(prefs.get() ?: "")

        btn.setOnClickListener {
            val num = edt.text.toString().trim()
            if (num.length < 10 || !num.all { it.isDigit() }) {
                Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show()
            } else {
                prefs.set(num)
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
