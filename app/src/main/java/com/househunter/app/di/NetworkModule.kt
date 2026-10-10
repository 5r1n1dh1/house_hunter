package com.househunter.app.di

import com.househunter.app.data.remote.HttpFetcher
import com.househunter.app.data.remote.NominatimApi
import com.househunter.app.data.remote.OverpassApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OverpassMirror

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideOverpassApi(client: OkHttpClient): OverpassApi = Retrofit.Builder()
        .baseUrl("https://overpass-api.de/api/")
        .client(client)
        .build()
        .create(OverpassApi::class.java)

    @Provides
    @Singleton
    @OverpassMirror
    fun provideOverpassMirrorApi(client: OkHttpClient): OverpassApi = Retrofit.Builder()
        .baseUrl("https://overpass.kumi.systems/api/")
        .client(client)
        .build()
        .create(OverpassApi::class.java)

    @Provides
    @Singleton
    fun provideNominatimApi(client: OkHttpClient): NominatimApi = Retrofit.Builder()
        .baseUrl("https://nominatim.openstreetmap.org/")
        .client(client)
        .build()
        .create(NominatimApi::class.java)

    @Provides
    @Singleton
    fun provideHttpFetcher(client: OkHttpClient): HttpFetcher = HttpFetcher(client)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}