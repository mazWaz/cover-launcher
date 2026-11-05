package com.mazwas.covercam.di

import com.mazwas.covercam.CameraEngine
import com.mazwas.covercam.ui.camera.CameraViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for the camera feature.
 *
 * The engine is a singleton: it owns the camera hardware and survives activity recreation, which
 * is what we want on a cover screen that sleeps often. The ViewModel is one per activity scope.
 */
val coverCamModule = module {
  single { CameraEngine(androidContext()) }
  viewModel { CameraViewModel(engine = get()) }
}
