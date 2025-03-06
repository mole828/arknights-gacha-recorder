package com.example.api

import com.example.api.ArkNights.Companion.json
import com.example.service.GachaRecorder
import fuel.FuelBuilder
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import java.io.File

import kotlin.test.Test

class ArkNightsApiTest {
    val api = ArkNights.default()
    val gachaApi = ArkNights.GachaApi.default()
//    val hgToken = File("src/main/resources/token.test.json").readText().let {
//        Json.decodeFromString<ArkNights.HgTokenResponse>(it).data
//    }
    val hgToken = ArkNights.HgToken("KBOTRkngWzyyukYBC+rsvCrm")
    val appToken = runBlocking { api.grantAppToken(hgToken) }
    val bindingList = runBlocking { api.bindingList(appToken) }
    val uid = bindingList.list.first().bindingList.first().uid
    val u8Token = runBlocking { api.u8TokenByUid(appToken, uid) }

    @Test
    fun checkToken() {
        runBlocking {
            api.checkToken(hgToken)
            println(bindingList)
            println(u8Token)
        }
    }

    @Test
    fun testHistory() {
        val fuel = FuelBuilder().build()
        runBlocking {
            val loginCookie = api.login(u8Token)

//            println("u8Token: $u8Token")
//            api.info(u8Token)
//
//            api.login(u8Token)
            val pools = gachaApi.poolList(uid, u8Token, loginCookie)
//            println(pools)
//
            val re = gachaApi.history(
                loginCookie = loginCookie,
                u8Token = u8Token,
                uid = uid,
                pool = ArkNights.GachaApi.Pool("spring_fest", ""),
                size = 10u,
                gachaTs = "1737532727523".toULong(),
                pos = 8u,
            )
            println(re)
        }
    }

    @Test
    fun testService() {
        val db = Database.connect(
            url = "jdbc:postgresql://localhost:5432/app",
            driver = "org.postgresql.Driver",
            user = "app",
            password = "app",
        )
        val service = GachaRecorder(db)
        service.upsert(accountInfo = bindingList.list.first().bindingList.first(), hgToken = hgToken)
        runBlocking {
            val total = service.updateGacha(hgToken)
            println(total)
        }
    }

    @Test
    fun testLogin() {
        runBlocking {
            val c = api.login(u8Token)
            println(c)
        }
    }
}