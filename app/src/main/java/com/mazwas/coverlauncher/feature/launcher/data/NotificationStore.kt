package com.mazwas.coverlauncher.feature.launcher.data

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mazwas.coverlauncher.feature.launcher.domain.LauncherNotification
import com.mazwas.coverlauncher.feature.launcher.domain.notificationActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    return LauncherNotification(key, packageName, label(packageName), title, text, postTime, isClearable, actions, media)
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
  override fun onNotificationPosted(sbn: StatusBarNotification?) = store.publish()
  override fun onNotificationRemoved(sbn: StatusBarNotification?) = store.publish()
}
