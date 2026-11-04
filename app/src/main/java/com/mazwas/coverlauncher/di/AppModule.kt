package com.mazwas.coverlauncher.di

import com.mazwas.coverlauncher.core.system.Clock
import com.mazwas.coverlauncher.feature.cover.Grants
import com.mazwas.coverlauncher.feature.launcher.data.AppsSource
import com.mazwas.coverlauncher.feature.launcher.data.CoverOpener
import com.mazwas.coverlauncher.feature.launcher.data.IconLoader
import com.mazwas.coverlauncher.feature.launcher.data.NotificationStore
import com.mazwas.coverlauncher.feature.launcher.data.SettingsStore
import com.mazwas.coverlauncher.feature.launcher.data.SystemStatusSource
import com.mazwas.coverlauncher.feature.launcher.presentation.LauncherViewModel
import com.mazwas.coverlauncher.feature.settings.presentation.SettingsViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
  singleOf(::Clock)
  singleOf(::Grants)
  singleOf(::AppsSource)
  singleOf(::SettingsStore)
  singleOf(::NotificationStore)
  singleOf(::SystemStatusSource)
  singleOf(::IconLoader)
  singleOf(::CoverOpener)
  viewModelOf(::LauncherViewModel)
  viewModelOf(::SettingsViewModel)
}
