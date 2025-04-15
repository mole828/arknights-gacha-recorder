package com.example.controller

import agent.Agent
import agent.MessageTemplate
import agent.WebSocketAgent
import api.ArkNights
import api.Uid
import com.example.service.GachaRecorder
import com.example.service.GachaRecorder.UserTable
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

fun Routing.agentPart(
    agentKey: String,
    service: GachaRecorder,
    delayTime: kotlin.time.Duration = 2.minutes
): Pair<MutableList<Agent>, MutableList<MessageTemplate.Task>> {
    val log = LoggerFactory.getLogger("AgentRoute")

    log.info("agentKey: $agentKey")
    log.info("delayTime: $delayTime")

    val agentList = mutableListOf<Agent>()
    get("/agent/status") {
        call.respond(agentList)
    }

    val taskList = mutableListOf<MessageTemplate.Task>()
    fun getTask(): MessageTemplate.Task {
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
                            MessageTemplate.Task(
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


    val scope = CoroutineScope(Dispatchers.Default)
    webSocket("/agent/ws") {
        val agent = WebSocketAgent(this)
        agent.waitAuth(agentKey)
        agentList.add(agent)
        val fn: suspend (MessageTemplate) -> Unit = { msg ->
            log.info("收到消息: ${msg.toString().substring(0..20)}")  // 打印前20个字符
            when(msg) {
                is MessageTemplate.TaskResult -> {
                    log.info("收到任务结果 ${msg.uid}, 共计 ${msg.result.size} 条")
                    service.record(msg.result.map { ArkNights.GachaApi.Gacha.from(uid = msg.uid, gachaInfo = it) })
                    transaction {
                        UserTable.update(
                            where = { UserTable.hgToken eq msg.hgToken.content },
                            body = {
                                it[UserTable.expired] = false
                            },
                        )
                    }
                    scope.launch {
                        delay(delayTime)
                        val task = getTask()
                        agent.send(MessageTemplate.Task(
                            hgToken = task.hgToken,
                            uid = task.uid,
                        ))
                    }
                }
                is MessageTemplate.Expired -> {
                    transaction {
                        UserTable.update(
                            where = { UserTable.hgToken eq msg.hgToken.content },
                            body = {
                                it[UserTable.expired] = true
                            },
                        )
                    }
                }
                is MessageTemplate.UserInfo -> {
                    log.info("更新用户信息: ${msg.info}")
                    transaction {
                        UserTable.upsert {
                            it[UserTable.uid] = msg.info.uid
                            it[UserTable.nickName] = msg.info.nickName
                            it[UserTable.channelMasterId] = msg.info.channelMasterId
                            it[UserTable.channelName] = msg.info.channelName
                            it[UserTable.isDefault] = msg.info.isDefault
                            it[UserTable.isDeleted] = msg.info.isDeleted
                            it[UserTable.isOfficial] = msg.info.isOfficial
                            it[UserTable.hgToken] = msg.hgToken.content
                        }
                    }
                }
                else -> {}
            }
        }
        agent.onMessageActions.add(fn)
        log.info("开始派发任务")
        agent.send(getTask().let {
            MessageTemplate.Task(
                hgToken = it.hgToken,
                uid = it.uid,
            )
        })
        agent.reading.await()
        agentList.remove(agent)
    }

    return agentList to taskList
}