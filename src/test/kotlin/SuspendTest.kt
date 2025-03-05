package com.example

import kotlin.test.Test
import io.ktor.client.network.sockets.*
import org.jetbrains.exposed.sql.transactions.experimental.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.Database

class SuspendTest {
    @Test
    fun test() {
        Database.connect(url = "jdbc:postgresql://localhost:5432/app", driver = "org.postgresql.Driver", user = "app", password = "app")
        // 模拟存在网络请求的数据库操作
        suspend fun fakeUpdateGacha() {
            delay(100)
            throw ConnectTimeoutException("模拟超时") // 精确抛出你的异常类型
        }

        suspend fun main() = coroutineScope {
            val handler = CoroutineExceptionHandler { _, e ->
                println("全局捕获: ${e.javaClass.simpleName}")
                e.printStackTrace()
            }
            val scope = CoroutineScope(SupervisorJob() + handler)

            scope.launch {
                supervisorScope {

                    try {
                        newSuspendedTransaction () { // 重点观察此处
//                            try {
                                fakeUpdateGacha() // 模拟你的updateGacha
//                            }catch (e: Throwable) {
//                                println("内部捕获成功")
//                                throw e
//                            }
                        }
                    } catch (e: Throwable) {
                        println("内部捕获成功")
                        throw e
                    }
                }
            }

            delay(3000)
        }

        runBlocking {
            main()
        }
    }
}