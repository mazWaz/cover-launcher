package com.mazwas.coverlauncher.feature.launcher.domain

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

  @Test fun `notification age rounds down to the largest unit`() {
    val now = 10L * 24 * 3_600_000
    assertEquals(listOf("NOW", "NOW", "5M", "3H", "2D"), listOf(now + 1_000, now - 59_000, now - 300_000, now - 3 * 3_600_000 - 1, now - 2 * 86_400_000L).map { relativeAge(it, now) })
  }
}
