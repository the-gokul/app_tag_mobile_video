package com.nordic.tagmobile

import android.app.Application
import com.nordic.tagmobile.ble.TagBleManager
import com.nordic.tagmobile.ble.TagBleScanner
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger

class TagApp : Application() {
    lateinit var bleManager: TagBleManager
        private set
    lateinit var bleScanner: TagBleScanner
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        TagLogger.init(this)
        TagLogger.log(LogCategory.APP, "APP_START", "Tag mobile started")
        bleManager = TagBleManager(this)
        bleScanner = TagBleScanner(this)
    }

    companion object {
        lateinit var instance: TagApp
            private set
    }
}
