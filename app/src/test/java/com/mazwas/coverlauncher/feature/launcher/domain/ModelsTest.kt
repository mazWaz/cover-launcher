package com.mazwas.coverlauncher.feature.launcher.domain

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
  private fun app(pkg: String, label: String) = LaunchableApp(pkg, "$pkg.Main", label)

  // already A–Z, like AppsSource emits them
  private val apps = listOf(
    app("a", "Calculator"), app("b", "Camera"), app("c", "Google"), app("d", "Scanner"), app("e", "Settings"),
  )

  @Test fun `blank query keeps everything`() = assertEquals(apps, filterApps(apps, "  "))

  @Test fun `prefix matches first, then substring matches, case-insensitive`() =
    assertEquals(listOf("Calculator", "Camera", "Scanner"), filterApps(apps, "CA").map { it.label })

  @Test fun `default dock prefers known apps, then fills up`() {
    val dock = resolveDock(null, apps + app("com.sec.android.app.camera", "Kamera"))
    assertEquals(listOf("Kamera", "Calculator", "Camera", "Google"), dock.map { it.label })
  }

  @Test fun `saved dock keeps order and drops uninstalled apps`() {
    val dock = resolveDock(listOf("e/e.Main", "gone/x", "a/a.Main"), apps)
    assertEquals(listOf("Settings", "Calculator"), dock.map { it.label })
  }

  @Test fun `sound tile cycles ring, vibrate, silent and counts only silent as off`() {
    assertEquals(listOf(SoundMode.Vibrate, SoundMode.Silent, SoundMode.Ring), SoundMode.entries.map { it.next })
    assertEquals(listOf(true, true, false), SoundMode.entries.map { SystemStatus(sound = it).isOn(QuickToggle.Sound) })
  }

  @Test fun `notification buttons are the first three, none for calls`() {
    val titles = listOf("Reply", "Mark as read", "Mute", "Archive")
    assertEquals(listOf("Reply", "Mark as read", "Mute"), notificationActions("msg", titles))
    assertEquals(titles.take(3), notificationActions(null, titles))
    assertEquals(emptyList<String>(), notificationActions("call", listOf("Decline", "Answer")))
  }

  @Test fun `pop-up only for new or re-alerting important notifications`() {
    val high = NotificationManager.IMPORTANCE_HIGH
    assertEquals(true, shouldPopUp(0, high, isUpdate = false, passesDnd = true))
    assertEquals(true, shouldPopUp(0, high, isUpdate = true, passesDnd = true)) // a new message in the same chat
    assertEquals(true, shouldPopUp(Notification.FLAG_ONLY_ALERT_ONCE, high, isUpdate = false, passesDnd = true))
    assertEquals(false, shouldPopUp(Notification.FLAG_ONLY_ALERT_ONCE, high, isUpdate = true, passesDnd = true))
    assertEquals(false, shouldPopUp(0, NotificationManager.IMPORTANCE_DEFAULT, isUpdate = false, passesDnd = true))
    assertEquals(false, shouldPopUp(0, high, isUpdate = false, passesDnd = false))
    assertEquals(false, shouldPopUp(Notification.FLAG_GROUP_SUMMARY, high, isUpdate = false, passesDnd = true))
    assertEquals(false, shouldPopUp(Notification.FLAG_ONGOING_EVENT, high, isUpdate = false, passesDnd = true))
  }

  @Test fun `flipped apps turn the cover upside down, others follow the rotate tile`() {
    val flipped = setOf("com.mobile.legends")
    assertEquals(2, coverRotation("com.mobile.legends", flipped, auto = true, lockedAt = 1, sensed = 3))
    assertEquals(3, coverRotation("launcher", flipped, auto = true, lockedAt = 1, sensed = 3))
    assertEquals(1, coverRotation("launcher", flipped, auto = false, lockedAt = 1, sensed = 3))
  }

  @Test fun `cover list only adds missing apps to slot 26 and keeps every other slot`() {
    val head = List(25) { "0" }.joinToString("/")
    val phone = "$head/backup,0;com.android.chrome,0/0/0/0/" // shape seen on the phone: 30 slots, trailing '/'
    assertEquals("$head/backup,0;com.android.chrome,0;a.b,0/0/0/0/", withCoverApps(phone, listOf("com.android.chrome", "a.b", "a.b")))
    assertEquals(null, withCoverApps(phone, listOf("com.android.chrome"))) // nothing to add: no write
    assertEquals("$head/a.b,0;c.d,0/0/0/0/", withCoverApps("$head/0/0/0/0/", listOf("a.b", "c.d"))) // empty slot
    assertEquals(null, withCoverApps("0/0/0", listOf("a.b"))) // not the format we know: leave it alone
    assertEquals(null, withCoverApps(null, listOf("a.b")))
  }

  @Test fun `a media session needs a title to show the player`() {
    assertEquals(NowPlaying("m", "Song", "Band", playing = true), nowPlaying("m", "Song", "Band", true))
    assertEquals(NowPlaying("m", "Song", "", playing = false), nowPlaying("m", "Song", null, false))
    assertEquals(null, nowPlaying("m", "  ", "Band", true))
    assertEquals(null, nowPlaying(null, "Song", "Band", true))
  }

  @Test fun `the player hides its own app's media notification only`() {
    fun note(key: String, pkg: String, media: Boolean) = LauncherNotification(key, pkg, pkg, "t", "x", 0, true, media = media)
    val notes = listOf(note("a", "music", media = true), note("b", "music", media = false), note("c", "video", media = true))
    assertEquals(listOf("b", "c"), listedNotifications(notes, NowPlaying("music", "Song", "", true)).map { it.key })
    assertEquals(notes, listedNotifications(notes, null))
  }

  @Test fun `cover brightness parses the two settings lines`() {
    assertEquals(CoverBrightness(100, auto = true), parseCoverBrightness("100\n1\n"))
    assertEquals(CoverBrightness(20, auto = false), parseCoverBrightness("20\n0\n"))
    assertEquals(CoverBrightness(255, auto = false), parseCoverBrightness("300\nnull\n")) // clamped; no mode = manual
    assertEquals(null, parseCoverBrightness("null\n1\n")) // key missing on this phone: no slider
    assertEquals(null, parseCoverBrightness(null))
  }

  @Test fun `notification age rounds down to the largest unit`() {
    val now = 10L * 24 * 3_600_000
    assertEquals(listOf("NOW", "NOW", "5M", "3H", "2D"), listOf(now + 1_000, now - 59_000, now - 300_000, now - 3 * 3_600_000 - 1, now - 2 * 86_400_000L).map { relativeAge(it, now) })
  }
}
