package com.example

import com.example.controller.agentPart
import com.example.controller.userPart
import com.example.service.GachaRecorder
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import kotlin.time.Duration.Companion.seconds


fun Application.configureRouting() {
    @Serializable
    class ResponseTemplate(
        val code: Int,
        val msg: String,
    )
    install(StatusPages) {
        exception<IllegalArgumentException> { call: ApplicationCall, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ResponseTemplate(code = -1, msg = cause.message ?: "parameter does not match")
            )
        }
    }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
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
    requireNotNull(agentKey)
    val agentPool = mutableListOf<Any>()

    routing {

        @Serializable
        data class HealthCheckStruct(
            val msg: String,
            val mainLoopRunning: Map<String, Boolean>,
        )
        get("/healthcheck") {
            call.respondText(
                Json.encodeToString(
                    HealthCheckStruct(
                        msg = "ok",
                        mainLoopRunning = service.mainLoopRunning(),
                    )
                ), ContentType.Application.Json
            )
        }


        val (agentList, taskList) = agentPart(agentKey, service)

        userPart(db = db, agentList = agentList, taskList = taskList)

        webSocket("/echo") {
            // 不能让函数体结束, 调用结束会关闭连接
            for (frame in incoming) {
                frame as? Frame.Text ?: continue
                send(frame)
            }
        }
    }
}
