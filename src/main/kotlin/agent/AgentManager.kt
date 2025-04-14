package agent

import api.ArkNights
import api.Uid
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.math.log

@Serializable
sealed interface MessageTemplate {
    @Serializable
    data class Msg(val msg: String) : MessageTemplate
    @Serializable
    data class Auth(val agentKey: String) : MessageTemplate
    @Serializable
    data class Task(val task: ArkNights.HgToken) : MessageTemplate
    @Serializable
    data class TaskResult(val result: List<ArkNights.GachaApi.GachaInfo.Companion.DefaultImpl>, val uid: Uid) : MessageTemplate
}

interface BasePeer {
    suspend fun send(msg: MessageTemplate)
    var onMessage: suspend (MessageTemplate) -> Unit
}

interface Agent : BasePeer {
    // for server use
    suspend fun waitAuth(agentKey: String) {
        val oldOnMessage = onMessage
        var authed = CompletableDeferred<Boolean>()
        this.onMessage = {
            if (it is MessageTemplate.Auth) {
                if (it.agentKey == agentKey) {
                    send(MessageTemplate.Msg("auth success"))
                    authed.complete(true)
                } else {
                    send(MessageTemplate.Msg("auth fail"))
                }
            } else {
                send(MessageTemplate.Msg("not auth"))
            }
        }
        if(authed.await().not()) {
            throw Exception("auth fail")
        }
        this.onMessage = oldOnMessage
    }
}
interface Server : BasePeer

class WebSocketAgent(val session: WebSocketSession) : Agent {
    companion object {
        val json = Json
        val scope = CoroutineScope(Dispatchers.Default)
        val logger = LoggerFactory.getLogger(WebSocketAgent::class.java)
    }
    override suspend fun send(msg: MessageTemplate) {
        val msgStr = json.encodeToString(msg)
        session.send(Frame.Text(msgStr))
    }
    override var onMessage: suspend (MessageTemplate) -> Unit = { msg ->
        println(msg)
    }
    val done = CompletableDeferred<Unit>()

    init {
        scope.launch {
            logger.info("agent start")
            try {
                for (frame in session.incoming) {
                    frame as? Frame.Text ?: continue
                    val receivedText = frame.readText()
                    val msg = json.decodeFromString<MessageTemplate>(receivedText)
                    onMessage(msg)
                }
            } catch (e: Throwable) {
                println(e)
            } finally {
                done.complete(Unit)
            }
            logger.info("agent end")
        }
    }
}

object AgentManager {
    fun WebSocketSession.agent(): Agent {
        return WebSocketAgent(this)
    }
}