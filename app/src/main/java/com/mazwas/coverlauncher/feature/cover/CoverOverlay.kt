package com.mazwas.coverlauncher.feature.cover

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mazwas.coverlauncher.core.designsystem.NothingTheme
import com.mazwas.coverlauncher.core.system.COVER_DISPLAY
import com.mazwas.coverlauncher.core.system.TAG
import com.mazwas.coverlauncher.feature.launcher.data.IconLoader
import com.mazwas.coverlauncher.feature.launcher.presentation.LocalIconLoader

/**
 * A Compose window on the cover above everything, apps and the lock screen included: an accessibility overlay (the
 * keep-awake window's type), so [context] must be the accessibility service. Null if the cover isn't there.
 */
internal fun addCoverOverlay(context: Context, icons: IconLoader, params: LayoutParams, content: @Composable () -> Unit): ComposeView? {
  val cover = context.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return null
  val window = context.createDisplayContext(cover).createWindowContext(LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
  val owner = AlwaysResumed()
  val view = ComposeView(window).apply {
    setViewTreeLifecycleOwner(owner)
    setViewTreeSavedStateRegistryOwner(owner)
    setContent { NothingTheme { CompositionLocalProvider(LocalIconLoader provides icons, content = content) } }
  }
  return runCatching { window.getSystemService(WindowManager::class.java).addView(view, params); view }
    .onFailure { Log.w(TAG, "cover overlay failed", it) }
    .getOrNull()
}

internal fun View.removeCoverOverlay() {
  runCatching { context.getSystemService(WindowManager::class.java).removeView(this) }
}

/** Compose outside an activity needs a lifecycle and a saved-state owner on its view tree; an overlay is always resumed. */
private class AlwaysResumed : SavedStateRegistryOwner {
  private val registry = LifecycleRegistry(this)
  private val saved = SavedStateRegistryController.create(this).apply { performRestore(null) }
  override val lifecycle: Lifecycle get() = registry
  override val savedStateRegistry get() = saved.savedStateRegistry

  init {
    registry.currentState = Lifecycle.State.RESUMED
  }
}
