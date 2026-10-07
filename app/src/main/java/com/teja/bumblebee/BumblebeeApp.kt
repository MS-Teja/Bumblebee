package com.teja.bumblebee

import android.app.Application
import com.teja.bumblebee.art.ArtLoader
import com.teja.bumblebee.data.Library
import com.teja.bumblebee.data.Prefs
import com.teja.bumblebee.index.Indexer
import com.teja.bumblebee.playback.QueueStore
import com.teja.bumblebee.ui.design.D
import com.teja.bumblebee.ui.design.Fonts

/** Cheap, synchronous init only: everything heavy (database, scans) happens lazily or in the background. */
class BumblebeeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Library.init(this)
        QueueStore.init(this)
        ArtLoader.init(this)
        Fonts.init(this)
        Indexer.init(this)
        D.init(this, Prefs.immersive)
    }
}
