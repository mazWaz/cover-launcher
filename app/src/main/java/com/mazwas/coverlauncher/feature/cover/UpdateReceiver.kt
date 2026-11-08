package com.mazwas.coverlauncher.feature.cover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mazwas.coverlauncher.core.system.coverOptions
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherActivity

/**
 * After an update nothing else starts the app (CoverService can sit "binding" until something does): bring the
 * launcher back, which starts App and with it Grants (and its rebind of a stuck CoverService). Boot needs nothing: the
 * system binds the accessibility service then.
 */
class UpdateReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    context.startActivity(Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), coverOptions())
  }
}
