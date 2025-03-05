package com.example

import kotlin.test.Test
import io.ktor.client.network.sockets.*
import org.jetbrains.exposed.sql.transactions.experimental.*
import kotlinx.coroutines.*

class SuspendTest {
    @Test
    fun test() {
        // 模拟存在网络请求的数据库操作
        suspend fun fakeUpdateGacha() {
            delay(100)
            throw ConnectTimeoutException("模拟超时") // 精确抛出你的异常类型
        }

        suspend fun main() = coroutineScope {
            val handler = CoroutineExceptionHandler { _, e ->
                println("全局捕获: ${e.javaClass.simpleName}")
            }
            val scope = CoroutineScope(SupervisorJob() + handler)

            scope.launch {
                try {
//                    newSuspendedTransaction (coroutineContext) { // 重点观察此处
                        fakeUpdateGacha() // 模拟你的updateGacha
//                    }
                } catch (e: ConnectTimeoutException) {
                    println("内部捕获成功")
                }
            }

            delay(3000)
        }

        runBlocking {
            main()
        }
    }
}