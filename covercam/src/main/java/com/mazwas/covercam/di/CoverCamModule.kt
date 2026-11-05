package com.mazwas.covercam.di

import com.mazwas.covercam.CameraEngine
import com.mazwas.covercam.ui.camera.CameraViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Koin module for the camera feature.
 *
 * The engine is a singleton: it owns the camera hardware and survives activity recreation, which
 * is what we want on a cover screen that sleeps often. The ViewModel is one per activity scope.
 *
 * Constructor DSL (`singleOf`/`viewModelOf`) per the project's DI conventions — no lambdas, no
 * deprecated `viewModel { }` builder.
 */
val coverCamModule = module {
  singleOf(::CameraEngine)
  viewModelOf(::CameraViewModel)
}
