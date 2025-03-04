package com.example

import com.example.api.ArkNights
import com.example.service.GachaRecorder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.time.Duration.Companion.minutes

fun Application.configureRouting() {
    @Serializable
    class ResponseTemplate (
        val code: Int,
        val msg: String,
    )
    install(StatusPages) {
        exception<IllegalArgumentException> { call: ApplicationCall, cause ->
            call.respond(HttpStatusCode.BadRequest, ResponseTemplate(code = -1, msg = cause.message?: "parameter does not match"))
        }
    }

    val dbUrl = System.getenv()["DATABASE_URL"]
    requireNotNull(dbUrl) {
        "DATABASE_URL is not set"
    }
    val dbUser = System.getenv()["DATABASE_USER"]
    requireNotNull(dbUser) {
        "DATABASE_USER is not set"
    }
    val dbPassword = System.getenv()["DATABASE_PASSWORD"]
    requireNotNull(dbPassword) {
        "DATABASE_PASSWORD is not set"
    }

    val db = Database.connect(
        url = dbUrl,
        driver = "org.postgresql.Driver",
        user = dbUser,
        password = dbPassword,
    )
    val service = GachaRecorder(database = db)
    var lastBeginTime = Clock.System.now()
    service.mainLoop(onBegin = {
        log.info("begin record")
        lastBeginTime = Clock.System.now()
    }, onEnd = { result ->
        log.info("end record, total: $result, spent: ${Clock.System.now() - lastBeginTime}")
        delay(2.minutes)
    }, onError = {
        log.info("mainLoop 出现错误", it)
        it.printStackTrace()
    }, onUserDone = {
        log.info("更新用户数据, nickName: ${it.nickName}, total: ${it.total}")
    })
    routing {
        get("/healthcheck") {
            call.respondText("ok")
        }

        get("/users") {
            val users = transaction(db) {
                GachaRecorder.UserTable.selectAll().map { ArkNights.AccountInfo(
                    uid = ArkNights.Uid(it[GachaRecorder.UserTable.uid]),
                    nickName = it[GachaRecorder.UserTable.nickName],
                    channelMasterId = it[GachaRecorder.UserTable.channelMasterId],
                    channelName = it[GachaRecorder.UserTable.channelName],
                    isDefault = it[GachaRecorder.UserTable.isDefault],
                    isDeleted = it[GachaRecorder.UserTable.isDeleted],
                    isOfficial = it[GachaRecorder.UserTable.isOfficial],
                ) }
            }
            call.respond(users)
        }
        get("/users.invalid") {
            val users = transaction(db) {
                GachaRecorder.UserTable.selectAll().where {
                    GachaRecorder.UserTable.expired eq true
                }.map { ArkNights.AccountInfo(
                    uid = ArkNights.Uid(it[GachaRecorder.UserTable.uid]),
                    nickName = it[GachaRecorder.UserTable.nickName],
                    channelMasterId = it[GachaRecorder.UserTable.channelMasterId],
                    channelName = it[GachaRecorder.UserTable.channelName],
                    isDefault = it[GachaRecorder.UserTable.isDefault],
                    isDeleted = it[GachaRecorder.UserTable.isDeleted],
                    isOfficial = it[GachaRecorder.UserTable.isOfficial],
                ) }
            }
            call.respond(users.map { it.nickName })
        }

        @Serializable
        data class Char(
            val isNew: Boolean,
            val rarity: UInt,
            val name: String,
        )
        @Serializable
        data class Draw(
            val chars: List<Char>,
            val nickName: String,
            val pool: String,
            val ts: ULong,
            val uid: ArkNights.Uid,
        )
        @Serializable
        data class Pagination(
            val current: ULong,
            val pageSize: Int,
            val total: ULong,
        )
        @Serializable
        data class GachasResponseData(
            val list: List<Draw>,
            val pagination: Pagination,
        )
        @Serializable
        data class GachasResponse(
            val data: GachasResponseData,
        )
        val pageSize = 10
        get("/gachas") {
            val page: Long = call.queryParameters["page"]?.toLong() ?: 0
            val uid: String? = call.queryParameters["uid"]
            val sql = GachaRecorder.GachaTable.leftJoin(GachaRecorder.UserTable).select(GachaRecorder.GachaTable.columns + listOf(GachaRecorder.UserTable.nickName)).apply {
                uid?.let { andWhere { GachaRecorder.GachaTable.uid eq uid } }
                orderBy(GachaRecorder.GachaTable.gachaTs, SortOrder.DESC)
                orderBy(GachaRecorder.GachaTable.pos, SortOrder.DESC)
            }
            val resp = transaction(db) {
                val pagination = sql.count().let { total ->
                    Pagination(
                        current = page.toULong(),
                        pageSize = pageSize,
                        total = total.toULong(),
                    )
                }
                val draws = sql.limit(pageSize).offset(page * pageSize).map { row ->
                    Draw(
                        chars = listOf(Char(
                            isNew = row[GachaRecorder.GachaTable.isNew],
                            rarity = row[GachaRecorder.GachaTable.rarity],
                            name = row[GachaRecorder.GachaTable.charName],
                        )),
                        nickName = row[GachaRecorder.UserTable.nickName],
                        pool = row[GachaRecorder.GachaTable.poolName],
                        ts = row[GachaRecorder.GachaTable.gachaTs] / 1000u,
                        uid = ArkNights.Uid(row[GachaRecorder.GachaTable.uid]),
                    )
                }
                GachasResponse(
                    data = GachasResponseData(
                        list = draws,
                        pagination = pagination,
                    )
                )
            }

            call.respondText(Json.encodeToString(resp), ContentType.Application.Json)
        }

        post("/register") {
            val tokenPerhaps = call.queryParameters["token"]
            log.info("register: $tokenPerhaps")
            requireNotNull(tokenPerhaps) { "token is required" }
            val hgToken = run {
                val hgToken = ArkNights.HgToken(tokenPerhaps)
                if (service.arkCenterApi.checkToken(hgToken)) {
                    return@run hgToken
                }
                val hgTokenResponse = kotlin.runCatching {
                    Json.decodeFromString<ArkNights.HgTokenResponse>(tokenPerhaps)
                } .getOrNull() ?: throw IllegalArgumentException("token is invalid")
                require(service.arkCenterApi.checkToken(hgTokenResponse.data)) { "token is invalid" }
                return@run hgTokenResponse.data
            }
//            val hgToken = ArkNights.HgToken(tokenPerhaps)
//            require(service.arkCenterApi.checkToken(hgToken))
            val appToken = service.arkCenterApi.grantAppToken(hgToken)
            val bindingList = service.arkCenterApi.bindingList(appToken)
            val account = bindingList.list.first().bindingList.first()
            launch {
                service.upsert(account, hgToken)
                val total = service.updateGacha(hgToken)
                log.info("update: $total")
            }
            call.respond(mapOf("msg" to "ok", "token" to hgToken.content))
        }


    }
}
