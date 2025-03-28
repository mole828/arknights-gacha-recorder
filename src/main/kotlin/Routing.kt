package com.example

import com.example.api.ArkNights
import com.example.api.ArkNights.GachaApi.GachaListData
import com.example.service.GachaRecorder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.uuid.Uuid

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

    val openLoop = System.getenv()["OPEN_LOOP"]?.toBoolean() ?: false
    log.info("OPEN_LOOP: $openLoop")
    if (openLoop) {
        monitor.subscribe(ServerReady) {
            service.scope.launch {
                service.mainLoop()
            }
        }
    }
    val agentKey = System.getenv()["AGENT_KEY"]

    routing {
        @Serializable
        data class HealthCheckStruct(
            val msg: String,
            val mainLoopRunning: Map<String, Boolean>,
        )
        get("/healthcheck") {
            call.respondText(Json.encodeToString(HealthCheckStruct(
                msg = "ok",
                mainLoopRunning = service.mainLoopRunning(),
            )), ContentType.Application.Json)
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

        if (agentKey != null) {
            log.info("agentKey: $agentKey")
            val agentList = mutableListOf<String>()
            get("/agent/status") {
                call.respond(agentList)
            }

            @Serializable
            data class Task (
                val uid: ArkNights.Uid,
                val hgToken: ArkNights.HgToken,
            )
            val taskList = mutableListOf<Task>()
            fun getTask(): Task {
                if (taskList.isEmpty()) {
                    log.info("reload taskList")
                    transaction {
                        GachaRecorder
                            .UserTable
                            .select(GachaRecorder.UserTable.uid, GachaRecorder.UserTable.hgToken)
                            .where {
                                GachaRecorder.UserTable.expired eq false
                            }.forEach {
                                taskList.add(
                                    Task(
                                        uid = ArkNights.Uid(it[GachaRecorder.UserTable.uid]),
                                        hgToken = ArkNights.HgToken(it[GachaRecorder.UserTable.hgToken]),
                                    )
                                )
                            }
                    }
                    taskList.shuffle()
                }
                val task = taskList.removeFirst()
                return task
            }

            get("/agent/task") {
                val key = call.parameters["agentKey"]
                requireNotNull(key)
                require(agentKey == key)
                val task = getTask()
                call.respond(task)
            }

            @Serializable
            data class TaskResult (
                val uid: ArkNights.Uid,
                val hgToken: ArkNights.HgToken,
                val gachas: List<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl>,
                val expired: Boolean? = false,
            )
            post("/agent/task") {
                val key = call.parameters["agentKey"]
                requireNotNull(key)
                require(agentKey == key)
                val body = call.receiveText()
                val result = Json.decodeFromString<TaskResult>(body)
                if (result.expired == true) {
                    transaction {
                        GachaRecorder.UserTable.update(
                            where = {GachaRecorder.UserTable.hgToken eq result.hgToken.content},
                            body = { it[GachaRecorder.UserTable.expired] = true }
                        )
                    }
                }
                log.info("agent 更新用户数据, total: ${result.gachas.size}")
                transaction {
                    service.record(result.gachas.map {
                        ArkNights.GachaApi.Gacha.from(
                            uid = result.uid,
                            gachaInfo = it,
                        )
                    })
                }
                call.respond(mapOf("msg" to "ok"))
            }
        }


    }
}
