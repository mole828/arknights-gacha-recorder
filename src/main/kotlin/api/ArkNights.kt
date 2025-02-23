package com.example.api

import fuel.FuelBuilder
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ArkNights {
    @JvmInline
    value class Uid(val value: ULong)
    @Serializable
    data class HgToken (val content: String)
    @Serializable
    data class HgTokenResponse (val code: Int, val data: HgToken, val msg: String)
    @Serializable
    data class GrantAppTokenPayload (
        val appCode: String,
        val token: String,
        val type: Int,
    )
    @Serializable
    data class AppToken (val hgId: String, val token: String)
    @Serializable
    data class AppTokenResponse (val status: Int, val data: AppToken, val msg: String, val type: String)
    suspend fun grantAppToken(hgToken: HgToken): AppToken

    @Serializable
    data class U8Token(val token: String)
    @Serializable
    data class U8TokenResponse(
        val status: Int,
        val msg: String,
        val data: U8Token? = null,
    )
    suspend fun u8TokenByUid(appToken: AppToken, uid: Uid): U8Token

    companion object {
        val fuel = FuelBuilder().build()
        fun default(): ArkNights {
            return object : ArkNights {
                override suspend fun grantAppToken(hgToken: HgToken): AppToken {
                    val resp = fuel.post {
                        url = "https://as.hypergryph.com/user/oauth2/v2/grant"
                        headers = mapOf("content-type" to "application/json",)
                        body = Json.encodeToString(ArkNights.GrantAppTokenPayload(
                            appCode = "be36d44aa36bfb5b",
                            token = hgToken.content,
                            type = 1,
                        ))
                    }
                    val body = resp.source.readString()
                    return Json.decodeFromString<AppTokenResponse>(body).data
                }
                override suspend fun u8TokenByUid(appToken: AppToken, uid: Uid): U8Token {
                    val resp = fuel.post {
                        url = "https://binding-api-account-prod.hypergryph.com/account/binding/v1/u8_token_by_uid"
                        headers = mapOf("content-type" to "application/json")
                        body = Json.encodeToString(mapOf(
                            "token" to appToken.token,
                            "uid" to uid.value.toString(),
                        ))
                    }
                    val body = resp.source.readString()
                    val re = Json.decodeFromString<U8TokenResponse>(body)
                    if (re.status != 0) {
                        throw IllegalStateException("$re")
                    }
                    requireNotNull(re.data)
                    return re.data
                }
            }
        }
    }

    interface GachaApi {
        interface GachaInfo {
            val charId: String
            val charName: String
            val gachaTs: String
            val isNew: Boolean
            val poolId: String
            val poolName: String
            val pos: UInt // 十连出现的位置 单抽为0 十连为0-9
            val rarity: UInt // 0-5
            fun primeKey(): String {
                return "${gachaTs}_${pos}"
            }
        }
        interface Gacha : GachaInfo {
            val uid: ULong // 用户id
            override fun primeKey(): String {
                return "${uid}_${super.primeKey()}"
            }
        }

        suspend fun xRoleToken(
            token: String,
        )

        suspend fun history(
            akUserCenterCookieContent: String,
            u8Token: U8Token,
            uid: ULong,
            category: String,
            size: UInt,
            gachaTs: String? = null,
            pos: UInt? = null,
        ) : List<Gacha>

        companion object {
            fun default(): GachaApi {
                return object : GachaApi {
                    // 获取token: https://web-api.hypergryph.com/account/info/hg
                    override suspend fun xRoleToken(token: String) {
                        fuel.post {
                            url = "https://binding-api-account-prod.hypergryph.com/account/binding/v1/u8_token_by_uid"
                            body = Json.encodeToString(mapOf(
                                "token" to token
                            ))
                        }
                        println(token)
                    }

                    override suspend fun history(
                        akUserCenterCookieContent: String,
                        u8Token: U8Token,
                        uid: ULong,
                        category: String,
                        size: UInt,
                        gachaTs: String?,
                        pos: UInt?,
                    ): List<Gacha> {
                        val url = "https://ak.hypergryph.com/user/api/inquiry/gacha/history"
                        val resp = fuel.get {
                            this.url = url
                            parameters = mutableListOf(
                                "uid" to uid.toString(),
                                "category" to "spring_fest",
                                "size" to size.toString(),
                            ).apply {
                                gachaTs?.let { add("gachaTs" to it) }
                                pos?.let { add("pos" to it.toString()) }
                            }
                            headers = mapOf(
                                "x-role-token" to u8Token.token,
                                "cookie" to mapOf(
                                    "ak-user-center" to akUserCenterCookieContent,
                                ).map { (k, v) -> "$k=$v" }.joinToString("; ")
                            )
                        }


                        println(resp.statusCode)
                        println(resp.source.readString())
                        return listOf<Gacha>()
                    }
                }
            }
        }
    }
}