package com.example.controller

import agent.Agent
import agent.MessageTemplate
import agent.MessageTemplate.Task
import api.ArkNights
import com.example.service.GachaRecorder
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.Char
import model.Draw
import model.GachasResponse
import model.GachasResponseData
import model.Pagination
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import kotlin.text.toLong
import kotlin.time.Duration.Companion.seconds

fun Routing.userPart(db: Database, taskList: MutableList<Task>, agentList: List<Agent>) {
//    val scope = CoroutineScope(Dispatchers.IO)
    val log = LoggerFactory.getLogger("UserRoute")
//    val logger =
    get("/users") {
        val users = transaction(db) {
            GachaRecorder.UserTable.selectAll().map { ArkNights.AccountInfo(
                uid = it[GachaRecorder.UserTable.uid],
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
                uid = it[GachaRecorder.UserTable.uid],
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
                    uid = row[GachaRecorder.GachaTable.uid],
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
//        val hgToken = run {
//            val hgToken = ArkNights.HgToken(tokenPerhaps)
//            if (service.arkCenterApi.checkToken(hgToken)) {
//                return@run hgToken
//            }
//            val hgTokenResponse = kotlin.runCatching {
//                Json.decodeFromString<ArkNights.HgTokenResponse>(tokenPerhaps)
//            } .getOrNull() ?: throw IllegalArgumentException("token is invalid")
//            require(service.arkCenterApi.checkToken(hgTokenResponse.data)) { "token is invalid" }
//            return@run hgTokenResponse.data
//        }
////            val hgToken = ArkNights.HgToken(tokenPerhaps)
////            require(service.arkCenterApi.checkToken(hgToken))
//        val appToken = service.arkCenterApi.grantAppToken(hgToken)
//        val bindingList = service.arkCenterApi.bindingList(appToken)
//        val account = bindingList.list.first()
//        launch {
//            service.upsert(account, hgToken)
//            val total = service.updateGacha(hgToken)
//            log.info("update: $total")
//        }
        val hgToken = try {
            Json.decodeFromString<ArkNights.HgTokenResponse>(tokenPerhaps).data
        } catch (e: Throwable) {
            ArkNights.HgToken(tokenPerhaps)
        }
        val agent = agentList.randomOrNull() ?: run {
            call.respond(mapOf("msg" to "提交成功, 请等待记录员处理"))
            return@post
        }

        val tokenValid = CompletableDeferred<MessageTemplate>()
        val fn: suspend (MessageTemplate) -> Unit = { msg ->
            when (msg) {
                is MessageTemplate.TokenValid -> {
                    tokenValid.complete(msg)
                }

                is MessageTemplate.Expired -> {
                    if (msg.hgToken.content == tokenPerhaps) {
                        tokenValid.complete(msg)
                    }
                }

                is MessageTemplate.TokenInvalid -> {
                    if (msg.hgToken.content == tokenPerhaps) {
                        tokenValid.complete(msg)
                    }
                }

                else -> {}
            }
        }
        agent.onMessageActions.add(fn)
        agent.send(MessageTemplate.Task(hgToken, null))

        val returnData = withTimeoutOrNull(10.seconds) {
            when(tokenValid.await()) {
                is MessageTemplate.TokenValid -> {
                    mapOf("msg" to "提交成功")
                }
                is MessageTemplate.TokenInvalid -> {
                    mapOf("msg" to "HgToken 无效")
                }
                is MessageTemplate.Expired -> {
                    mapOf("msg" to "HgToken 已过期")
                }
                else -> Error("未知错误")
            }
        }
        call.respond(returnData?: mapOf("msg" to "未知错误"))
    }
}