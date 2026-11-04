package com.mazwas.coverlauncher.core.system

import android.content.Context
import android.text.format.DateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime

class Clock(private val context: Context) {
  /** Now, then again on every minute boundary. Restarted on resume, so deep sleep can't leave it stale. */
  val ticks: Flow<LocalDateTime> = flow {
    while (true) {
      emit(LocalDateTime.now())
      delay(60_000 - System.currentTimeMillis() % 60_000)
    }
  }

  fun is24Hour(): Boolean = DateFormat.is24HourFormat(context)
}
