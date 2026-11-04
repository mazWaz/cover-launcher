package com.mazwas.coverlauncher

import android.app.Application
import com.mazwas.coverlauncher.di.appModule
import com.mazwas.coverlauncher.feature.cover.Grants
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import rikka.shizuku.Shizuku

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    startKoin {
      androidContext(this@App)
      modules(appModule)
    }
    // Shizuku comes up after every boot (and ShizukuProvider wakes us): re-apply fold state and permissions
    val grants: Grants = get()
    Shizuku.addBinderReceivedListenerSticky { grants.apply() }
  }
}
