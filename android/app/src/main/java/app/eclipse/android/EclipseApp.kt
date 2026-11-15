package app.eclipse.android

import android.app.Application
import app.eclipse.android.data.AppLog
import app.eclipse.android.data.AppSettings
import app.eclipse.android.data.Store

class EclipseApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.init(this)
        settings = AppSettings(this)
        store = Store(this, settings)
    }

    companion object {
        lateinit var instance: EclipseApp
            private set
    }
}
