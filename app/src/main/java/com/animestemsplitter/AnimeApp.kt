package com.animestemsplitter

import android.app.Application
import com.animestemsplitter.data.Diagnostics

class AnimeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
    }
}
