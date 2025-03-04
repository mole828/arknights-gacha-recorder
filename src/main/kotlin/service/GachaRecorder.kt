package com.example.service

import com.example.api.ArkNights
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.time.Duration.Companion.minutes

class GachaRecorder(private val database: Database) {
    object UserTable: Table("user") {
        val uid = text("uid")
        override val primaryKey = PrimaryKey(uid)

        val nickName = text("nick_name")
        val hgToken = text("hg_token")

        val expired = bool("expired").default(false)

        val channelMasterId = integer("channel_master_id").default(1)
        val channelName = text("channel_name").default("官服")
        val isDefault = bool("is_default").default(false)
        val isDeleted = bool("is_deleted").default(false)
        val isOfficial = bool("is_official").default(true)
    }

    object GachaTable: Table("gacha") {
        val uid = reference("uid", UserTable.uid)
        val gachaTs = ulong("gacha_ts")
        val pos = uinteger("pos")
        override val primaryKey = PrimaryKey(uid, gachaTs, pos)

        val charId = text("char_id")
        val charName = text("char_name")
        val poolId = text("pool_id")
        val poolName = text("pool_name")
        val rarity = uinteger("rarity")
        val isNew = bool("is_new")
    }

    init {
        transaction(database) {
            SchemaUtils.create(UserTable)
            SchemaUtils.create(GachaTable)
        }
    }

    fun upsert(accountInfo: ArkNights.AccountInfo, hgToken: ArkNights.HgToken) {
        transaction(database) {
            UserTable.upsert (
                where = { UserTable.uid eq accountInfo.uid.value },
                body = { row ->
                    row[UserTable.uid] = accountInfo.uid.value
                    row[UserTable.hgToken] = hgToken.content

                    row[UserTable.nickName] = accountInfo.nickName
                    row[UserTable.expired] = false
                    row[UserTable.channelMasterId] = accountInfo.channelMasterId
                    row[UserTable.channelName] = accountInfo.channelName
                    row[UserTable.isDefault] = accountInfo.isDefault
                    row[UserTable.isDeleted] = accountInfo.isDeleted
                    row[UserTable.isOfficial] = accountInfo.isOfficial
                }
            )
        }
    }
    fun update(accountInfo: ArkNights.AccountInfo) {
        transaction (database) {
            UserTable.update(
                where = { UserTable.uid eq accountInfo.uid.value },
                body = { row ->
                    row[UserTable.nickName] = accountInfo.nickName
                    row[UserTable.channelMasterId] = accountInfo.channelMasterId
                    row[UserTable.channelName] = accountInfo.channelName
                    row[UserTable.isDefault] = accountInfo.isDefault
                    row[UserTable.isDeleted] = accountInfo.isDeleted
                    row[UserTable.isOfficial] = accountInfo.isOfficial
                }
            )
        }
    }
    private fun expire(uid: ArkNights.Uid) {
        transaction(database) {
            UserTable.update(
                where = { UserTable.uid eq uid.value },
                body = { row ->
                    row[UserTable.expired] = true
                }
            )
        }
    }
    private fun expire(hgToken: ArkNights.HgToken) {
        transaction(database) {
            UserTable.update(
                where = { UserTable.hgToken eq hgToken.content },
                body = { row ->
                    row[UserTable.expired] = true
                }
            )
        }
    }

    fun exists(uid: ArkNights.Uid, gachaTs: ULong, pos: UInt): Boolean {
        return transaction(database) {
            GachaTable.select(GachaTable.uid).where {
                (GachaTable.uid eq uid.value).and(GachaTable.gachaTs eq gachaTs).and(GachaTable.pos eq pos)
            }.count() > 0
        }
    }

    fun record(gacha: ArkNights.GachaApi.Gacha): UInt {
        return transaction(database) {
            if( exists(gacha.uid, gacha.gachaTs, gacha.pos) ) {
                return@transaction 0u
            }
            GachaTable.insert {
                it[GachaTable.uid] = gacha.uid.value
                it[GachaTable.gachaTs] = gacha.gachaTs
                it[GachaTable.pos] = gacha.pos

                it[GachaTable.charId] = gacha.charId
                it[GachaTable.charName] = gacha.charName
                it[GachaTable.poolId] = gacha.poolId
                it[GachaTable.poolName] = gacha.poolName
                it[GachaTable.rarity] = gacha.rarity
                it[GachaTable.isNew] = gacha.isNew
            }
            1u
        }
    }
    fun record(list: List<ArkNights.GachaApi.Gacha>): UInt = list.sumOf { record(it) }

    val arkCenterApi = ArkNights.default()
    val gachaApi = ArkNights.GachaApi.default()
    suspend fun updateGacha(hgToken: ArkNights.HgToken, size: UInt = 10u) : UInt {
        require(arkCenterApi.checkToken(hgToken)) {
            expire(hgToken)
            "hgToken 无效"
        }
        val appToken = arkCenterApi.grantAppToken(hgToken)
        val bindingList= arkCenterApi.bindingList(appToken = appToken)
        val appBindings = bindingList.list.first()
        require(appBindings.appCode == "arknights") {
            "这是什么? $appBindings"
        }
        val account = appBindings.bindingList.first()
        update(account)
        val uid = account.uid
        val u8Token = arkCenterApi.u8TokenByUid(appToken, uid)
        val loginCookie = arkCenterApi.login(u8Token)
        val poolList = gachaApi.poolList(uid, u8Token, loginCookie)
        var total = 0u
        poolList.forEach { pool ->
            var history = gachaApi.history(loginCookie, u8Token, uid, pool, size = size)
            var thisBatch = 1u
            while (thisBatch > 0u) {
                thisBatch = record(history.list.map { ArkNights.GachaApi.Gacha.from(uid, it) })
                total += thisBatch
                if(!history.hasMore) break
                val last = history.list.last()
                history = gachaApi.history(loginCookie, u8Token, uid, pool, size = size, gachaTs = last.gachaTs, pos = last.pos)
            }
        }
        return total
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    data class UpdateResult(var sum: UInt)
    data class UserUpdateResult(val nickName: String, var total: UInt)
    fun mainLoop(
        onBegin: suspend () -> Unit = {},
        onEnd: suspend (UpdateResult) -> Unit = {},
        onError: suspend (Throwable) -> Unit = {},
        onUserDone: suspend (UserUpdateResult) -> Unit = {},
    ) {
        scope.launch {
            while (true) {
                onBegin()

                val hgTokenMap = transaction (database) {
                    UserTable.select(UserTable.hgToken, UserTable.nickName).where {
                        UserTable.expired eq false
                    }.map {
                        ArkNights.HgToken(content = it[UserTable.hgToken]) to it[UserTable.nickName]
                    }
                }.shuffled()
                val total = hgTokenMap.sumOf {
                    val (hgToken, nickName) = it
                    delay(1.minutes)
                    try {
                        val userUpdateResult = UserUpdateResult(total = newSuspendedTransaction {
                            updateGacha(hgToken)
                        }, nickName = nickName)
                        onUserDone(userUpdateResult)
                        userUpdateResult.total
                    } catch (e: io.ktor.client.network.sockets.ConnectTimeoutException) {
                        // 可能是香港与大陆的问题，尝试一次 忽略
                        0u
                    } catch (e: Throwable) {
                        onError(e)
                        0u
                    }
                }
                onEnd(UpdateResult(total))
            }
        }
    }
    fun mainLoopRunning() = run {
        val job = scope.coroutineContext[Job]
        requireNotNull(job)
        job.children.toList().associate {
            it.key.toString() to it.isActive
        }
    }

    fun cancel() {
        scope
    }
}