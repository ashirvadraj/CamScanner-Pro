package com.camscanner.pro

import android.app.Application
import com.camscanner.pro.data.repository.DocumentRepository

class CamScannerApp : Application() {

    lateinit var repository: DocumentRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = DocumentRepository(this)
    }

    companion object {
        lateinit var instance: CamScannerApp
            private set
    }
}
