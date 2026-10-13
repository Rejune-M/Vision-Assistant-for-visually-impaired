package com.example.visionassist

import android.app.Application
import com.example.visionassist.db.AppDatabase

class VisionAssistApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
