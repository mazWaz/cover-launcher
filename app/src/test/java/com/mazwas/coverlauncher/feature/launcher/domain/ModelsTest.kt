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

  @Test fun `pop-up over a secure lock follows the lock-screen notification rules`() {
    val private = Notification.VISIBILITY_PRIVATE
    assertEquals(LockedView.Full, lockedView(secureLocked = false, showOnLock = false, showPrivate = false, private))
    assertEquals(LockedView.Full, lockedView(secureLocked = true, showOnLock = true, showPrivate = true, private))
    assertEquals(LockedView.Public, lockedView(secureLocked = true, showOnLock = true, showPrivate = false, private))
    assertEquals(LockedView.Full, lockedView(true, true, false, Notification.VISIBILITY_PUBLIC))
    assertEquals(LockedView.Hidden, lockedView(true, true, true, Notification.VISIBILITY_SECRET))
    assertEquals(LockedView.Hidden, lockedView(true, showOnLock = false, showPrivate = true, Notification.VISIBILITY_PUBLIC))
  }

  @Test fun `an app's own rotation wins, others follow the rotate tile`() {
    val own = mapOf("com.mobile.legends" to 2, "reader" to 0)
    assertEquals(2, coverRotation("com.mobile.legends", own, auto = true, lockedAt = 1, sensed = 3))
    assertEquals(0, coverRotation("reader", own, auto = true, lockedAt = 1, sensed = 3))
    assertEquals(3, coverRotation("launcher", own, auto = true, lockedAt = 1, sensed = 3))
    assertEquals(1, coverRotation("launcher", own, auto = false, lockedAt = 1, sensed = 3))
  }

  @Test fun `tile order keeps the saved one, drops unknown names, appends new tiles, moves stop at the ends`() {
    val order = tileOrder(listOf("Torch", "Gone", "Wifi"))
    assertEquals(listOf(QuickToggle.Torch, QuickToggle.Wifi, QuickToggle.Bluetooth), order.take(3))
    assertEquals(QuickToggle.entries.size, order.size)
    assertEquals(QuickToggle.entries, tileOrder(null))
    assertEquals(listOf(QuickToggle.Wifi, QuickToggle.Torch), order.moved(QuickToggle.Wifi, -1).take(2))
    assertEquals(order, order.moved(QuickToggle.Torch, -1)) // already first
    assertEquals(QuickToggle.Torch, order.moved(QuickToggle.Torch, 99).last())
  }

  @Test fun `battery heat warns at 42, acts at 45, and needs a degree less to step down`() {
    assertEquals(Heat.Normal, heat(371, Heat.Normal))
    assertEquals(Heat.Warm, heat(420, Heat.Normal))
    assertEquals(Heat.Hot, heat(450, Heat.Warm))
    assertEquals(Heat.Hot, heat(445, Heat.Hot)) // not yet 44.0
    assertEquals(Heat.Warm, heat(439, Heat.Hot))
    assertEquals(Heat.Warm, heat(415, Heat.Warm)) // not yet 41.0
    assertEquals(Heat.Normal, heat(409, Heat.Warm))
    assertEquals(Heat.Normal, heat(415, Heat.Normal)) // rising: still under 42
  }

  @Test fun `per-app values survive the DataStore string set and cycle back to default`() {
    val values = mapOf("com.mobile.legends" to 2, "a.b" to 280)
    assertEquals(values, values.toEntrySet().toIntMap())
    assertEquals(emptyMap<String, Int>(), setOf("=2", "no-value", "a.b=x").toIntMap()) // malformed: dropped
    assertEquals(listOf(0, 1, 2, 3, null), listOf(null, 0, 1, 2, 3).map { APP_ROTATIONS.after(it) })
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
