package com.example.api

import fuel.FuelBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

import kotlin.test.Test

class ArkNightsApiTest {
    val api = ArkNights.default()
    val gachaApi = ArkNights.GachaApi.default()
    val hgToken = File("src/main/resources/token.test.json").readText().let {
        Json.decodeFromString<ArkNights.HgTokenResponse>(it).data
    }
    val fuel = FuelBuilder().build()

    @Test
    fun testGrantAppToken() {
        runBlocking {
            val appToken = api.grantAppToken(hgToken)
            println(appToken)
        }
    }

    @Test
    fun testU8TokenByUid() {
        runBlocking {
            val appToken = api.grantAppToken(hgToken)
            val u8Token = api.u8TokenByUid(appToken, ArkNights.Uid(69023059u))
            println(u8Token)
        }
    }

    @Test
    fun testXRoleToken() {
        runBlocking {
            gachaApi.xRoleToken(hgToken.content)
        }
    }

    @Test
    fun testHistory() {
        runBlocking {
            val re = gachaApi.history(
                akUserCenterCookieContent = "nDhVczM4XvenHw3TbAbT9yw945zp93vQVZNjkqEr6rg6ULAP3f8OFNIf6t9dJYxrXhQmKSVcgesRAD5sqj63Oke%2BpX7tOJav%2BVdT4jIgj0qYn3nWwZdT1eIKn5F4IBi2qI9rzs8f1AOwctxlfHc1MQpLYs3BnmrAWDz69f7Tz9qeaDRamU0OBQEybYFI3TLG2xjhyl%2BagdkFEhhl%2BW5gdVP6rWDohdaPweJ%2FsDix1X3IAFR4YPdbcfdyoeJjZnenDmVzbdEaSWwFl%2BKMA37OH02zURPG%2B%2BUjhRP0nNIJ%2BNw3G6ZQ8ZItwy%2Bk2U4AxuDvMpDmUTMlCompn7tpuXGBWAINBCs7E28gfXzxpLmWrHhPu7FX%2Bdt6WRg8oBw9CiReOBDrNQcFSo1THYChAR5XllOZVpLqZ7f4lY04MjiMMZc%3D",
                u8Token = "291j7yBftDAQ74ko2UkNByQ3bUSoz1LblQbByWjOgLpW5VVxVJAu9hQEunRBGkBfKV+0zB8igDEOARSBOMmMGQEabu7PCGJcBxRPB+OI7h0sc1IuKTqbs6MUvwoEZgm9sl40LY43IGvH45EZ7uBUd8eo3+Zdk4P91bbVaIsDcoWCDRrMDEy4",
                uid = 69023059u,
                category = "spring_fest",
                size = 10u,
                gachaTs = "1737532727523",
                pos = 8u,
            )
            println(re)
        }
    }
}