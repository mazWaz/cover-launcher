package com.mazwas.coverlauncher.core.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Clock(private val context: Context) {
  /**
   * Now, then on every minute (the system's TIME_TICK) and whenever the time or zone is changed. Not a delay(): that
   * counts uptime, which stops while the CPU sleeps, so the dozing cover's clock froze once nothing held the CPU (music
   * stopped) and caught up late. TIME_TICK kept coming every minute, even in forced deep idle (2026-11-08).
   */
  val ticks: Flow<LocalDateTime> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        trySend(LocalDateTime.now())
      }
    }
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_TIME_TICK)
      addAction(Intent.ACTION_TIME_CHANGED)
      addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) // all three come from the system
    trySend(LocalDateTime.now())
    awaitClose { context.unregisterReceiver(receiver) }
  }

  fun is24Hour(): Boolean = DateFormat.is24HourFormat(context)

  /** "22:28" or "10:28", as the system is set. */
  fun time(now: LocalDateTime): String = now.format(if (is24Hour()) H24 else H12)

  /** "WED 04 NOV". */
  fun date(now: LocalDateTime): String = now.format(DATE).uppercase()

  private companion object {
    val H24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val H12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd MMM")
  }
}
