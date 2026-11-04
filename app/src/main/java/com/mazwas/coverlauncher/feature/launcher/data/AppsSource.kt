package com.mazwas.coverlauncher.feature.launcher.data

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import com.mazwas.coverlauncher.feature.launcher.domain.LaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Launchable apps A–Z, re-read whenever a package is installed, removed or updated. */
class AppsSource(context: Context) {
  private val launcherApps = context.getSystemService(LauncherApps::class.java)
  private val self = context.packageName

  val apps: Flow<List<LaunchableApp>> = callbackFlow {
    val changed = object : LauncherApps.Callback() {
      override fun onPackageRemoved(packageName: String, user: UserHandle) { trySend(Unit) }
      override fun onPackageAdded(packageName: String, user: UserHandle) { trySend(Unit) }
      override fun onPackageChanged(packageName: String, user: UserHandle) { trySend(Unit) }
      override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { trySend(Unit) }
      override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) { trySend(Unit) }
    }
    trySend(Unit)
    launcherApps.registerCallback(changed, Handler(Looper.getMainLooper()))
    awaitClose { launcherApps.unregisterCallback(changed) }
  }.conflate().map { load() }.flowOn(Dispatchers.IO)

  private fun load(): List<LaunchableApp> =
    launcherApps.getActivityList(null, Process.myUserHandle())
      .filter { it.componentName.packageName != self }
      .map { LaunchableApp(it.componentName.packageName, it.componentName.className, it.label.toString()) }
      .sortedBy { it.label.lowercase() }
}
