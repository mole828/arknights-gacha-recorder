package agent

import api.ArkNights
import api.Uid
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
sealed interface MessageTemplate {
    @Serializable
    @SerialName("msg")
    data class Msg(val msg: String) : MessageTemplate
    @Serializable
    @SerialName("auth")
    data class Auth(val agentKey: String) : MessageTemplate
    @Serializable
    @SerialName("task")
    data class Task(val hgToken: ArkNights.HgToken, val uid: Uid?) : MessageTemplate
    @Serializable
    @SerialName("task_result")
    data class TaskResult(val result: List<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl>, val uid: Uid, val hgToken: ArkNights.HgToken) : MessageTemplate

    @Serializable
    @SerialName("expired")
    data class Expired(val hgToken: ArkNights.HgToken) : MessageTemplate
    @Serializable
    @SerialName("token_valid")
    data class TokenValid(val uid: Uid, val hgToken: ArkNights.HgToken) : MessageTemplate
    @Serializable
    @SerialName("token_invalid")
    data class TokenInvalid(val hgToken: ArkNights.HgToken, val msg: String) : MessageTemplate

    @Serializable
    @SerialName("user_info")
    data class UserInfo(val info: ArkNights.AccountInfo, val hgToken: ArkNights.HgToken) : MessageTemplate
}



interface BasePeer {
    suspend fun send(msg: MessageTemplate)
    val onMessageActions: MutableList<suspend (MessageTemplate) -> Unit>
    val scope: CoroutineScope
    suspend fun onMessage (msg: MessageTemplate): Unit {
        onMessageActions.forEach { scope.async{ it(msg) } }
    }
}

interface Agent : BasePeer {
    // for server use
    suspend fun waitAuth(agentKey: String) {

        var authed = CompletableDeferred<Boolean>()
        val fn: suspend (MessageTemplate) -> Unit = { msg ->
            if (msg is MessageTemplate.Auth) {
                if (msg.agentKey == agentKey) {
                    send(MessageTemplate.Msg("auth success"))
                    authed.complete(true)
                } else {
                    send(MessageTemplate.Msg("auth fail"))
                }
            } else {
                send(MessageTemplate.Msg("not auth"))
            }
        }
        try {
            this.onMessageActions.add(fn)
            if(authed.await().not()) {
                throw Exception("auth fail")
            }
        } catch (e: Throwable) {
            throw e
        } finally {
            this.onMessageActions.remove(fn)
        }
    }
}
interface Server : BasePeer

class WebSocketAgent(val session: WebSocketSession) : Agent {
    companion object {
        val json = Json
        private val scope = CoroutineScope(Dispatchers.Default)
        val logger = LoggerFactory.getLogger(WebSocketAgent::class.java)
    }
    override val scope = Companion.scope
    override suspend fun send(msg: MessageTemplate) {
        val msgStr = json.encodeToString(msg)
        session.send(Frame.Text(msgStr))
    }
    override val onMessageActions = mutableListOf<suspend (MessageTemplate) -> Unit>()
    val reading = scope.async {
        logger.info("agent start reading")
        try {
            for (frame in session.incoming) {
                frame as? Frame.Text ?: continue
                val receivedText = frame.readText()
                val msg = json.decodeFromString<MessageTemplate>(receivedText)
                onMessage(msg)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        logger.info("agent end reading")
    }
}