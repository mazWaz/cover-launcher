package com.mazwas.coverlauncher.feature.launcher.data

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mazwas.coverlauncher.core.system.TAG
import com.mazwas.coverlauncher.feature.launcher.domain.MediaCommand
import com.mazwas.coverlauncher.feature.launcher.domain.NowPlaying
import com.mazwas.coverlauncher.feature.launcher.domain.nowPlaying
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What plays now, from Android's media sessions. Reading them needs notification access, which [NotificationListener]
 * already has (Grants), so no new permission. Follows the top session only.
 */
class MediaSource(private val context: Context) {
  private val sessions = context.getSystemService(MediaSessionManager::class.java)
  private var current: MediaController? = null // main thread only, like the callbacks below

  val nowPlaying: Flow<NowPlaying?> = callbackFlow {
    val main = Handler(Looper.getMainLooper())
    fun publish() {
      val c = current
      val state = c?.playbackState?.state
      trySend(nowPlaying(
        c?.packageName,
        c?.metadata?.getText(MediaMetadata.METADATA_KEY_TITLE),
        c?.metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST),
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING,
      ))
    }
    val changes = object : MediaController.Callback() {
      override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
      override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
    }
    fun follow(list: List<MediaController>?) {
      current?.unregisterCallback(changes)
      current = list?.firstOrNull()?.also { it.registerCallback(changes, main) }
      publish()
    }
    val listChanged = MediaSessionManager.OnActiveSessionsChangedListener(::follow)
    val listener = ComponentName(context, NotificationListener::class.java)
    // throws until notification access is granted; the flow restarts when the launcher comes back
    runCatching {
      sessions.addOnActiveSessionsChangedListener(listChanged, listener, main)
      val now = sessions.getActiveSessions(listener)
      main.post { follow(now) }
    }.onFailure { Log.w(TAG, "media sessions unavailable", it); trySend(null) }
    awaitClose {
      runCatching { sessions.removeOnActiveSessionsChangedListener(listChanged) }
      main.post { current?.unregisterCallback(changes); current = null }
    }
  }.distinctUntilChanged()

  fun command(command: MediaCommand) {
    val c = current ?: return
    val controls = c.transportControls
    when (command) {
      MediaCommand.Previous -> controls.skipToPrevious()
      MediaCommand.Next -> controls.skipToNext()
      MediaCommand.PlayPause -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
    }
  }
}
