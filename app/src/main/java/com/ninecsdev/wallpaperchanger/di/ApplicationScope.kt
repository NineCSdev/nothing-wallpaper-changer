package com.ninecsdev.wallpaperchanger.di

import javax.inject.Qualifier

/** The process-lived scope: work that must finish even though the screen that asked for it is gone. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
