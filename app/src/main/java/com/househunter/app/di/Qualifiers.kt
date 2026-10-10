package com.househunter.app.di

import javax.inject.Qualifier

/**
 * Qualifiers live in their own file: declaring them inside the @Module object
 * makes KSP fail with `error.NonExistentClass` when resolving them.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OverpassMirror