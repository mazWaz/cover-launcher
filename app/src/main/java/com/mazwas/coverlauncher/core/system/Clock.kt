package com.mazwas.coverlauncher.core.system

import android.content.Context
import android.text.format.DateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Clock(private val context: Context) {
  /** Now, then again on every minute boundary. Restarted on resume, so deep sleep can't leave it stale. */
  val ticks: Flow<LocalDateTime> = flow {
    while (true) {
      emit(LocalDateTime.now())
      delay(60_000 - System.currentTimeMillis() % 60_000)
    }
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
