package com.mazwas.coverlauncher.core.system

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import kotlin.concurrent.thread

const val TAG = "CoverLauncher"

/** Z Flip5 cover screen. */
const val COVER_DISPLAY = 1

/** The (dead) main screen. */
const val MAIN_DISPLAY = 0

/** Keep-awake tile: the launcher writes [KEEP_AWAKE], CoverService holds the cover on while it is set. */
fun Context.keepAwakePrefs(): SharedPreferences = getSharedPreferences("keep_awake", Context.MODE_PRIVATE)
const val KEEP_AWAKE = "on"

/** Start options that put an activity (or a notification's PendingIntent) on the cover. */
@Suppress("DEPRECATION") // ALLOW_ALWAYS is API 36; minSdk is 34
fun coverOptions(): Bundle = ActivityOptions.makeBasic()
  .setLaunchDisplayId(COVER_DISPLAY)
  .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
  .toBundle()

/** The cover keyguard stays up until something opens; it's swipe-only, so this dismisses it right away. */
fun Activity.unlockThen(block: () -> Unit) {
  val keyguard = getSystemService(KeyguardManager::class.java)
  if (!keyguard.isKeyguardLocked) return block()
  keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
    override fun onDismissSucceeded() = block()
  })
}

// Visible root tasks on the main display, minus home tasks (Samsung's launcher, the post-update setup wizard)
// and split-screen containers, moved to the cover. Moving a home root task between displays crashes system_server.
// Polls ~3 s: the app may still be starting (e.g. a notification broadcast whose receiver opens an activity).
private val PULL_TO_COVER = """
  for i in 1 2 3 4 5 6; do
    am stack list | awk '/^RootTask/ { r = substr(${'$'}2, 4); main = / displayId=$MAIN_DISPLAY / }
      /mActivityType=home/ { main = 0 }
      main && /visible=true/ && !/com.sec.android.app.launcher|: unknown/ && !seen[r]++ { print r }' |
    while read r; do am display move-stack ${'$'}r $COVER_DISPLAY && echo "moved ${'$'}r"; done
    sleep 0.5
  done
""".trimIndent()

/**
 * Apps that reuse an old task (or start from a broadcast) open where that task lives, often the dead main screen.
 * Moves anything showing there to the cover.
 */
fun pullToCover() = Shell.exec("sh", "-c", PULL_TO_COVER)

// Folding the phone back cancels the CLOSED override (seen 2026-11-08: it survives opening, not closing), so the next
// unfold would wake the dead main screen. Re-request it on every hinge change. Our own request logs
// requestStateInternal, never onStateChanged, so the keeper can't trigger itself; the 1 s lets the old hold drop first.
private val KEEP_FOLDED = """
  nohup sh -c 'logcat -T 1 -s DeviceStateManagerService:D | while read -r l; do
    case "${'$'}l" in *onStateChanged*) sleep 1; cmd device_state state 0 ;; esac
  done' coverlauncher-fold >/dev/null 2>&1 &
""".trimIndent()

/**
 * Starts the fold keeper (process name `coverlauncher-fold`), replacing any earlier one. Shell user only: never run it
 * as the app, or a later `pkill` from the shell couldn't stop it (OpenCover leaked watchers that way).
 */
fun keepFolded() = thread {
  // Brackets keep the patterns off their own command line. The old logcat goes too: its sh only exits once it does.
  Shell.exec("sh", "-c", "pkill -f '[c]overlauncher-fold'; pkill -f '[l]ogcat -T 1 -s DeviceStateManagerService:D'").join()
  Shell.exec("sh", "-c", KEEP_FOLDED)
}
