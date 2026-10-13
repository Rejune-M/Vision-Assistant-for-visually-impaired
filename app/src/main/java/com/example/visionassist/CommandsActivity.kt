package com.example.visionassist

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CommandsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_commands)

        findViewById<TextView>(R.id.txtCommands).text = VoiceCommands.HELP_TEXT.trimIndent()
    }
}
