package com.discgolf.distance

import android.app.Application
import com.discgolf.distance.data.AppDatabase

class DiscGolfApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
}
