package com.example.controller

import agent.AgentManager
import agent.MessageTemplate
import agent.WebSocketAgent
import api.ArkNights
import api.Uid
import com.example.service.GachaRecorder
import io.ktor.server.application.log
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

fun Routing.agentPart(agentKey: String, service: GachaRecorder) {
    val log = LoggerFactory.getLogger("AgentRoute")

    log.info("agentKey: $agentKey")
    val agentList = mutableListOf<String>()
    get("/agent/status") {
        call.respond(agentList)
    }

    @Serializable
    data class Task (
        val uid: Uid,
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
                                uid = it[GachaRecorder.UserTable.uid],
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
        val uid: Uid,
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
                    where = {GachaRecorder.UserTable.uid eq result.uid},
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


    val scope = CoroutineScope(Dispatchers.Default)
    webSocket("/agent/ws") {
        val agent = WebSocketAgent(this)
        agent.waitAuth(agentKey)
        agent.onMessage = { msg ->
            log.info("收到消息: $msg")
            when(msg) {
                is MessageTemplate.TaskResult -> {
                    log.info("收到任务结果: ${msg.result}")
                    service.record(msg.result.map { ArkNights.GachaApi.Gacha.from(uid = msg.uid, gachaInfo = it) })
                    scope.launch {
                        delay(5.minutes)
                        agent.send(MessageTemplate.Task(getTask().hgToken))
                    }
                }
                else -> {}
            }
        }
        log.info("开始派发任务")
        agent.done.await()
    }
}