package com.mazwas.coverlauncher.core.system

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/** Runs commands as the shell user (uid 2000) through Shizuku. */
object Shell {
  // ponytail: newProcess is private in Shizuku API 13 (they push UserService); reflection is 1 line vs an AIDL service. Move to UserService if a Shizuku update removes it.
  private val newProcess by lazy {
    Shizuku::class.java
      .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
      .apply { isAccessible = true }
  }

  fun ready(): Boolean =
    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

  fun exec(vararg cmd: String) = thread {
    runCatching {
      val p = newProcess.invoke(null, cmd, null, null) as Process
      val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
      Log.i(TAG, "shell ${cmd.joinToString(" ")} -> ${p.waitFor()} ${out.trim()}")
    }.onFailure { Log.e(TAG, "shell failed", it) }
  }
}
