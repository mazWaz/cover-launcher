package com.mazwas.coverlauncher

import android.app.Application
import android.content.pm.PackageManager
import com.mazwas.coverlauncher.core.system.Shell
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
    // Shizuku comes up after every boot (and ShizukuProvider wakes us): re-apply fold state and permissions.
    // On a fresh install the binder lands after the first screen is up, so the permission is asked for here.
    val grants: Grants = get()
    Shizuku.addBinderReceivedListenerSticky {
      if (!Shell.ready() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(0)
      grants.apply()
    }
    Shizuku.addRequestPermissionResultListener { _, result -> if (result == PackageManager.PERMISSION_GRANTED) grants.apply() }
  }
}
