package com.example.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

class ArkNightsApiSoleTest {
    val hgToken = File("src/main/resources/token.test.json").readText().let {
        Json.decodeFromString<ArkNights.HgTokenResponse>(it).data
    }
    @Test
    fun testToken() {
        val api = ArkNights.default()
        runBlocking {
            api.checkToken(hgToken)
        }
    }
}