package com.example

import com.example.api.ArkNights
import com.example.service.GachaRecorder
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.time.Duration.Companion.minutes

fun Application.configureRouting() {

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
    service.mainLoop(onBegin = {
        log.info("begin record")
    }, onEnd = { result ->
        log.info("end record, total: $result")
        delay(2.minutes)
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
            call.respond(users)
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
            val uid: ULong,
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
            val uid: ULong? = call.queryParameters["uid"]?.toULong()
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


    }
}
