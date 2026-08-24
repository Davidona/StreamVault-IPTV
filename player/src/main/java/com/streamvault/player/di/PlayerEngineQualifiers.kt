package com.streamvault.player.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainPlayerEngine

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuxiliaryPlayerEngine
