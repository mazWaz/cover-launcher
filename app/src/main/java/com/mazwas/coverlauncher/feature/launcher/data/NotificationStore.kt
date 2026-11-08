package com.mazwas.coverlauncher.feature.launcher.data

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mazwas.coverlauncher.core.system.TAG
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.LockedView
import com.mazwas.coverlauncher.feature.launcher.domain.lockedView
import com.mazwas.coverlauncher.feature.launcher.domain.notificationActions
import com.mazwas.coverlauncher.feature.launcher.domain.shouldPopUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap

/** What [NotificationListener] sees, for the panel. Holds the raw notifications so they can be opened and dismissed. */
class NotificationStore(private val context: Context) {
  private val raw = MutableStateFlow<List<StatusBarNotification>>(emptyList())
  private val _connected = MutableStateFlow(false)
  private val labels = ConcurrentHashMap<String, String>()
  internal var listener: NotificationListener? = null

  val connected: StateFlow<Boolean> = _connected.asStateFlow()

  val notifications: Flow<List<LauncherNotification>> = raw
    .map { list -> list.mapNotNull { it.toDomain() }.sortedByDescending { it.postedAt } }
    .flowOn(Dispatchers.Default)

  private val _popups = MutableSharedFlow<LauncherNotification>(extraBufferCapacity = 1)

  /** Notifications that just alerted, for CoverService's pop-up. */
  val popups: SharedFlow<LauncherNotification> = _popups.asSharedFlow()

  fun get(key: String): StatusBarNotification? = raw.value.firstOrNull { it.key == key }
  fun dismiss(key: String) { listener?.cancelNotification(key) }
  fun clearAll() { listener?.cancelAllNotifications() }

  internal fun attach(service: NotificationListener?) {
    listener = service
    _connected.value = service != null
    publish()
  }

  internal fun publish() {
    raw.value = listener?.let { runCatching { it.activeNotifications.toList() }.getOrNull() }.orEmpty()
  }

  internal fun posted(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking) {
    val isUpdate = raw.value.any { it.key == sbn.key } // read before publish() adds it
    publish()
    if (sbn.packageName != context.packageName &&
      shouldPopUp(sbn.notification.flags, ranking.importance, isUpdate, ranking.matchesInterruptionFilter())
    ) sbn.forLockScreen(ranking)?.let(_popups::tryEmit)
  }

  /** The pop-up's version of it: whole, its public version, or none, as the lock screen would show it ([lockedView]). */
  private fun StatusBarNotification.forLockScreen(ranking: NotificationListenerService.Ranking): LauncherNotification? {
    val full = toDomain() ?: return null
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    val channel = ranking.channel?.lockscreenVisibility?.takeIf { it != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE }
    val view = lockedView(
      secureLocked = keyguard.isKeyguardLocked && keyguard.isDeviceSecure,
      showOnLock = secureSetting("lock_screen_show_notifications", unreadable = true),
      showPrivate = secureSetting("lock_screen_allow_private_notifications", unreadable = false),
      visibility = channel ?: notification.visibility,
    )
    return when (view) {
      LockedView.Full -> full
      LockedView.Hidden -> null
      LockedView.Public -> {
        val public = notification.publicVersion?.extras
        val title = public?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = public?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        full.copy(title = title, text = text.ifBlank { if (title.isBlank()) "contents hidden" else "" }, actions = emptyList(), reply = -1)
      }
    }
  }

  // Hidden Settings.Secure keys Android's own lock screen reads (1 = on). Unreadable: pop up, but without private content.
  private fun secureSetting(key: String, unreadable: Boolean): Boolean =
    runCatching { Settings.Secure.getInt(context.contentResolver, key, 1) == 1 }
      .onFailure { Log.w(TAG, "can't read $key", it) }
      .getOrDefault(unreadable)

  private fun StatusBarNotification.toDomain(): LauncherNotification? {
    val n = notification
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null // its children are listed anyway
    val extras = n.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
      ?.toString().orEmpty()
    if (title.isBlank() && text.isBlank()) return null
    val actions = notificationActions(n.category, n.actions.orEmpty().map { it.title?.toString().orEmpty() })
    val media = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    // the button that takes typed text: the reply box instead of opening the app
    val reply = n.actions.orEmpty().take(actions.size).indexOfFirst { a -> a.remoteInputs.orEmpty().any { it.allowFreeFormInput } }
    return LauncherNotification(key, packageName, label(packageName), title, text, postTime, isClearable, actions, media, reply)
  }

  private fun label(pkg: String): String = labels.getOrPut(pkg) {
    val pm = context.packageManager
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
  }
}

/** Enabled with `cmd notification allow_listener` (Grants does it through Shizuku). */
class NotificationListener : NotificationListenerService() {
  private val store: NotificationStore by inject()

  override fun onListenerConnected() = store.attach(this)
  override fun onListenerDisconnected() = store.attach(null)
  override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
    val ranking = Ranking()
    if (sbn != null && rankingMap?.getRanking(sbn.key, ranking) == true) store.posted(sbn, ranking) else store.publish()
  }
  override fun onNotificationRemoved(sbn: StatusBarNotification?) = store.publish()
}
