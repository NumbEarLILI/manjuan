package com.numbear.manjuan

import android.app.Application
import com.numbear.manjuan.data.crypto.WebDavCipher
import com.numbear.manjuan.data.db.ManjuanDatabase
import com.numbear.manjuan.data.repo.LibraryRepository
import com.numbear.manjuan.data.settings.SettingsRepository

class ManjuanApp : Application() {
    lateinit var library: LibraryRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = ManjuanDatabase.create(this)
        library = LibraryRepository(this, database, WebDavCipher(this))
        settings = SettingsRepository(this)
    }
}
