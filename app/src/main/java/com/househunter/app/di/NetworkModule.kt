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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Overpass (Apache fronted) answers **406 Not Acceptable** to requests without
     * an identifying User-Agent, so this interceptor is mandatory, not cosmetic.
     */
    private val headersInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", HttpFetcher.USER_AGENT)
            .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .build()
        chain.proceed(request)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(headersInterceptor)
        .build()

    /** Overpass queries are slow by nature (heavy spatial containment), so give them room. */
    @Provides
    @Singleton
    @OverpassHttpClient
    fun provideOverpassClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(headersInterceptor)
        .build()

    /**
     * All public Overpass mirrors, tried in order by [com.househunter.app.data.repository.LocationRepository].
     * The public instances 504 frequently under load, so rotation is required.
     */
    @Provides
    @Singleton
    fun provideOverpassApis(@OverpassHttpClient client: OkHttpClient): Set<@JvmSuppressWildcards OverpassApi> {
        val endpoints = listOf(
            "https://overpass-api.de/api/",
            "https://maps.mail.ru/osm/tools/overpass/api/",
            "https://overpass.kumi.systems/api/",
        )
        return endpoints.map { base ->
            Retrofit.Builder().baseUrl(base).client(client).build().create(OverpassApi::class.java)
        }.toSet()
    }

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