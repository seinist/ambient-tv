package com.ambient.tv.di

import javax.inject.Qualifier

/** Shared OkHttp ConnectionPool / Dispatcher for API + health clients. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SharedOkHttp
