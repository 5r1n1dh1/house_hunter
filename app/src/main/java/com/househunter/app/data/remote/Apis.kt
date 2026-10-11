package com.househunter.app.data.remote

import okhttp3.ResponseBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface OverpassApi {
    @FormUrlEncoded
    @POST("interpreter")
    suspend fun run(@Field("data") query: String): ResponseBody
}

interface NominatimApi {
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("limit") limit: Int = 10,
        @Query("countrycodes") countryCodes: String = "in",
        @Query("addressdetails") addressDetails: Int = 0,
    ): ResponseBody
}