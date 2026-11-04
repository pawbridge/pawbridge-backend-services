package com.pawbridge.backend.animal.internal.lostsearch.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import feign.Client
import feign.Feign
import feign.Headers
import feign.Logger
import feign.Param
import feign.Request
import feign.RequestLine
import feign.Retryer
import feign.codec.Decoder
import feign.form.FormData
import feign.form.FormEncoder
import java.util.concurrent.TimeUnit

/** Selected original multipart POST using the module's existing native Feign composition. */
class PythonLostSearchClient internal constructor(
    baseUrl: String,
    mapper: ObjectMapper,
    client: Client,
) {
    private val endpoint: Endpoint = Feign.builder()
        .client(client)
        .options(Request.Options(3, TimeUnit.SECONDS, 45, TimeUnit.SECONDS, false))
        .retryer(Retryer.NEVER_RETRY)
        .logLevel(Logger.Level.NONE)
        .encoder(FormEncoder())
        .decoder(
            Decoder { response, _ ->
                if (response.body() == null) {
                    null
                } else {
                    mapper.readValue(response.body().asInputStream(), PythonLostSearchResponse::class.java)
                }
            },
        )
        .target(Endpoint::class.java, baseUrl)

    constructor(baseUrl: String, mapper: ObjectMapper) : this(baseUrl, mapper, Client.Default(null, null))

    fun search(
        key: String,
        image: FormData,
        species: String,
        lostDate: String?,
        region: String?,
        description: String?,
        includeAdoptedOrReturned: Boolean,
        // null이면 파트를 보내지 않는다. 이 입력을 모르는 이전 네이티브는 모르는 파트를 422로 거부하므로 켰을 때만 보낸다.
        includeDeceased: Boolean?,
    ): PythonLostSearchResponse? {
        return endpoint.search(key, image, species, lostDate, region, description, includeAdoptedOrReturned, includeDeceased)
    }

    internal interface Endpoint {
        @RequestLine("POST /internal/animals/lost-candidates")
        @Headers("X-Internal-Api-Key: {key}", "Content-Type: multipart/form-data")
        fun search(
            @Param("key") key: String,
            @Param("image") image: FormData,
            @Param("species") species: String,
            @Param("lostDate") lostDate: String?,
            @Param("region") region: String?,
            @Param("description") description: String?,
            @Param("includeAdoptedOrReturned") includeAdoptedOrReturned: Boolean,
            @Param("includeDeceased") includeDeceased: Boolean?,
        ): PythonLostSearchResponse?
    }
}
