package com.example.api

import fuel.FuelBuilder
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface ArkNights {
    interface BaseResponse {
        val status: Int
        val msg: String
        ;
        fun ok(): Boolean = status == 0
        companion object {
            @Serializable
            data class DefaultImpl(
                override val status: Int,
                override val msg: String,
            ): BaseResponse
        }
    }

    @JvmInline
    @Serializable
    value class Uid(val value: ULong)
    @Serializable
    data class HgToken (val content: String) {
        // 获取token: https://web-api.hypergryph.com/account/info/hg
    }
    @Serializable
    data class CheckTokenResponse(override val status: Int, override val msg: String, val type: String): BaseResponse
    suspend fun checkToken(hgToken: HgToken) {
        val resp = fuel.get {
            url = "https://as.hypergryph.com/user/info/v1/basic"
            parameters = listOf(
                "token" to hgToken.content,
            )
        }
        val body = resp.source.readString()
        val re = json.decodeFromString<CheckTokenResponse>(body)
        require(re.ok())
    }

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
    data class AccountInfo(
        val channelMasterId: Int,
        val channelName: String,
        val isDefault: Boolean,
        val isDeleted: Boolean,
        val isOfficial: Boolean,
        val nickName: String,
        val uid: Uid,
    )
    @Serializable
    data class AppBinding(val appCode: String, val appName: String, val bindingList: List<AccountInfo>)
    @Serializable
    data class MultiAppBindingList(val list: List<AppBinding>)
    @Serializable
    data class BindingListResponse(val status: Int, val msg: String, val data: MultiAppBindingList)
    suspend fun bindingList(appToken: AppToken): MultiAppBindingList

    @Serializable
    data class U8Token(val token: String)
    @Serializable
    data class U8TokenResponse(
        val status: Int,
        val msg: String,
        val data: U8Token? = null,
    )
    suspend fun u8TokenByUid(appToken: AppToken, uid: Uid): U8Token

    suspend fun info(u8Token: U8Token) {
        val resp = fuel.get {
            url = "https://ak.hypergryph.com/user/api/role/info?source_from=&share_type=&share_by="
            headers = mapOf(
                "x-role-token" to u8Token.token,
            )
        }
        TODO()
    }
    data class LoginCookie(val akUserCenterCookieContent: String) {
        fun toPair(): Pair<String, String> = "ak-user-center" to akUserCenterCookieContent
    }
    suspend fun login(u8Token: U8Token): LoginCookie


    companion object {
        val fuel = FuelBuilder().build()
        val client = OkHttpClient()
        val json = Json {
            ignoreUnknownKeys = true
        }
        fun cookie(map: Map<String, String>): String = map.map { (k, v) -> "$k=$v" }.joinToString("; ")
        fun default(): ArkNights {
            return object : ArkNights {
                override suspend fun grantAppToken(hgToken: HgToken): AppToken {
                    val resp = fuel.post {
                        url = "https://as.hypergryph.com/user/oauth2/v2/grant"
                        headers = mapOf("content-type" to "application/json",)
                        body = Json.encodeToString(GrantAppTokenPayload(
                            appCode = "be36d44aa36bfb5b",
                            token = hgToken.content,
                            type = 1,
                        ))
                    }
                    val body = resp.source.readString()
                    return Json.decodeFromString<AppTokenResponse>(body).data
                }

                override suspend fun bindingList(appToken: AppToken): MultiAppBindingList {
                    val resp = fuel.get {
                        url = "https://binding-api-account-prod.hypergryph.com/account/binding/v1/binding_list"
                        parameters = listOf(
                            "token" to appToken.token,
                            "appCode" to "arknights",
                        )
                    }
                    val body = resp.source.readString()
                    return Json.decodeFromString<BindingListResponse>(body).data
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

                override suspend fun login(u8Token: U8Token): LoginCookie {
                    val mediaType = "application/json".toMediaType()
                    val requestBody = json.encodeToString(mapOf(
                        "token" to u8Token.token,
                        "source_from" to "",
                        "share_type" to "",
                        "share_by" to "",
                    ))
                    val request = Request.Builder()
                        .url("https://ak.hypergryph.com/user/api/role/login")
                        .post(requestBody.toRequestBody(mediaType))
                        .build()

                    val resp = client.newCall(request).execute()
                    val setCookie = resp.headers["Set-Cookie"]
                    require(setCookie != null)
                    val map = setCookie.split(";").map { it.trim() }.map { it.substringBefore("=") to it.substringAfter("=") }.toMap()
                    val akUserCenterCookieContent = map["ak-user-center"]
                    requireNotNull(akUserCenterCookieContent)
                    return LoginCookie(akUserCenterCookieContent)
                }
            }
        }
    }

    interface GachaApi {
        @Serializable
        data class Pool(
            val id: String,
            val name: String,
        )
        @Serializable
        data class PoolListResponse(
            val code: Int,
            val msg: String,
            val data: List<Pool>,
        )
        suspend fun poolList(uid: Uid, u8Token: U8Token, loginCookie: LoginCookie): List<Pool> {
            val resp = fuel.get {
                url = "https://ak.hypergryph.com/user/api/inquiry/gacha/cate"
                parameters = listOf("uid" to uid.value.toString())
                headers = mapOf(
                    "X-Role-Token" to u8Token.token,
                    "cookie" to cookie(mapOf(loginCookie.toPair()))
                )
            }
            val body = resp.source.readString()
            return json.decodeFromString<PoolListResponse>(body).data
        }

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
            companion object {
                @Serializable
                data class DefaultImpl(
                    override val charId: String,
                    override val charName: String,
                    override val gachaTs: String,
                    override val isNew: Boolean,
                    override val poolId: String,
                    override val poolName: String,
                    override val pos: UInt,
                    override val rarity: UInt
                ): GachaInfo
            }
        }
        interface Gacha : GachaInfo {
            val uid: ULong // 用户id
            override fun primeKey(): String {
                return "${uid}_${super.primeKey()}"
            }
        }
        @Serializable
        data class GachaListData(val list: List<GachaInfo.Companion.DefaultImpl>)
        @Serializable
        data class GachaResponse(
            val code: Int,
            val msg: String,
            val data: GachaListData,
        )
        suspend fun history(
            loginCookie: LoginCookie,
            u8Token: U8Token,
            uid: Uid,
            category: String,
            size: UInt,
            gachaTs: String? = null,
            pos: UInt? = null,
        ) : List<GachaInfo>

        companion object {
            fun default(): GachaApi {
                return object : GachaApi {

                    override suspend fun history(
                        loginCookie: LoginCookie,
                        u8Token: U8Token,
                        uid: Uid,
                        category: String,
                        size: UInt,
                        gachaTs: String?,
                        pos: UInt?,
                    ): List<GachaInfo> {
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
                                "cookie" to cookie(mapOf(loginCookie.toPair()))
                            )
                        }
                        val body = resp.source.readString()
                        val re = json.decodeFromString<GachaResponse>(body)
                        return re.data.list
                    }
                }
            }
        }
    }
}