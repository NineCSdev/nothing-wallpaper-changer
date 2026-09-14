package com.ninecsdev.wallpaperchanger.di

import javax.inject.Qualifier

/**
 * Marks the process-lived [kotlinx.coroutines.CoroutineScope]: work that must finish even though
 * the screen that asked for it is gone.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
