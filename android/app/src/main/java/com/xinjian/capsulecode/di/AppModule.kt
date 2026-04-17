package com.xinjian.capsulecode.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// NetworkModule already provides all network dependencies.
// This module exists as an extension point for future bindings.
@Module
@InstallIn(SingletonComponent::class)
object AppModule
