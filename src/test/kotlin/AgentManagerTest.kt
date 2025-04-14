package com.example

import api.ArkNights
import agent.MessageTemplate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test

class AgentManagerTest {
    @Test
    fun testMessage() {
        val msgList = listOf(
            MessageTemplate.Auth("token"),
            MessageTemplate.Task(ArkNights.HgToken("token")),
            MessageTemplate.TaskResult(emptyList(), "uid"),
        )
        val json = Json
        val str = json.encodeToString(msgList)
        println(str)
        println(json.decodeFromString<List<MessageTemplate>>(str))
    }
}