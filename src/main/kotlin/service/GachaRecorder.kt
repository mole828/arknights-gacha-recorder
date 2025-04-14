package com.example.service

import api.ArkNights
import api.Uid
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GachaRecorder(private val database: Database) {
    public var logger: Logger

    init {
        logger = LoggerFactory.getLogger(javaClass)
    }

    object UserTable : Table("user") {
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

    object GachaTable : Table("gacha") {
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
            UserTable.upsert(
                where = { UserTable.uid eq accountInfo.uid },
                body = { row ->
                    row[UserTable.uid] = accountInfo.uid
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
        transaction(database) {
            UserTable.update(
                where = { UserTable.uid eq accountInfo.uid },
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

    private fun expire(uid: Uid) {
        transaction(database) {
            UserTable.update(
                where = { UserTable.uid eq uid },
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

    fun exists(uid: Uid, gachaTs: ULong, pos: UInt): Boolean {
        return transaction(database) {
            GachaTable.select(GachaTable.uid).where {
                (GachaTable.uid eq uid).and(GachaTable.gachaTs eq gachaTs).and(GachaTable.pos eq pos)
            }.count() > 0
        }
    }

    fun record(gacha: ArkNights.GachaApi.Gacha): UInt {
        return transaction(database) {
            if (exists(gacha.uid, gacha.gachaTs, gacha.pos)) {
                return@transaction 0u
            }
            GachaTable.insert {
                it[GachaTable.uid] = gacha.uid
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

    inner class UserUpdateTask(
        val hgToken: ArkNights.HgToken,
    ) {
        var uid: Uid private set
        var nickName: String private set
        var u8Token: ArkNights.U8Token private set
        var loginCookie: ArkNights.LoginCookie private set
        var poolList: List<ArkNights.GachaApi.Pool> private set
        init {
            runBlocking {
                logger.debug("检查 hgToken 是否有效")
                require(arkCenterApi.checkToken(hgToken)) {
                    expire(hgToken)
                    "hgToken 无效"
                }
                logger.debug("生成 appToken")
                val appToken = arkCenterApi.grantAppToken(hgToken)
                logger.debug("获取绑定列表")
                val bindingList = arkCenterApi.bindingList(appToken = appToken)
                val appBindings = bindingList.list.first()
                require(appBindings.appCode == "arknights") {
                    "这是什么? $appBindings"
                }
                nickName = appBindings.bindingList.first().nickName
                val account = appBindings.bindingList.first()
                update(account)
                uid = account.uid
                logger.debug("$nickName 获取 u8Token")
                u8Token = arkCenterApi.u8TokenByUid(appToken, uid)
                logger.debug("$nickName 登录")
                loginCookie = arkCenterApi.login(u8Token)
                logger.debug("$nickName 获取卡池列表")
                poolList = gachaApi.poolList(uid, u8Token, loginCookie)
            }
        }
        suspend fun run(size: UInt = 10u): UInt {
            val waitInsert = mutableListOf<ArkNights.GachaApi.Gacha>()
            poolList.map { pool ->
                // 卡池名称中有换行符
                val poolName = pool.name.replace("\n", "-")
                var lastSize = waitInsert.size - 1
                logger.debug("$nickName 获取 $poolName 历史记录")
                var history = gachaApi.history(loginCookie, u8Token, uid, pool, size = size)
                while (waitInsert.size > lastSize) {
                    delay(5.seconds)
                    lastSize = waitInsert.size
                    val thisBatch = history.list.filter { exists(uid, it.gachaTs, it.pos) }
                        .map { ArkNights.GachaApi.Gacha.from(uid, it) }
                    waitInsert.addAll(thisBatch)

                    if (!history.hasMore) break
                    val last = history.list.last()
                    logger.debug("$nickName 获取 $poolName 历史记录, 已处理 ${history.list.size} 条记录")
                    history = gachaApi.history(
                        loginCookie,
                        u8Token,
                        uid,
                        pool,
                        size = size,
                        gachaTs = last.gachaTs,
                        pos = last.pos
                    )
                }
            }
            return transaction {
                record(waitInsert)
            }
        }
    }

    suspend fun updateGacha(hgToken: ArkNights.HgToken, size: UInt = 10u): UInt {
        return UserUpdateTask(hgToken).run(size)
    }

    val scope = CoroutineScope(
    Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { coroutineContext, throwable ->
                println("CoroutineExceptionHandler 捕获异常: $throwable")
                throwable.printStackTrace()
            }
    )

    data class UpdateResult(var sum: UInt)
    data class UserUpdateResult(val nickName: String, var total: UInt)
    data class OnUserContext(
        val nickName: String,
        val hgToken: ArkNights.HgToken,
    )

    suspend fun mainLoop(

        delayTime: suspend () -> Duration = { 2.minutes }
    ) {
        while (true) {
            val roundBeginTime = Clock.System.now()
            logger.info("开始抓取用户数据")
            val hgTokenMap = transaction(database) {
                UserTable.select(UserTable.hgToken, UserTable.nickName).where {
                    UserTable.expired eq false
                }.map {
                    ArkNights.HgToken(content = it[UserTable.hgToken]) to it[UserTable.nickName]
                }
            }.shuffled()
            val total = hgTokenMap.sumOf {
                val (hgToken, nickName) = it
                    logger.info("更新用户数据, nickName: $nickName")
                val result = try {
                    updateGacha(hgToken)
                } catch (e: Throwable) {
                    logger.error("更新用户数据失败", e)
                    0u
                }
                delay(delayTime())
                result
            }
            logger.info("本轮更新用户数据, total: $total")
            logger.info("本轮更新用户数据, 耗时: ${Clock.System.now() - roundBeginTime}")
        }
    }

    fun mainLoopRunning() = run {
        val job = scope.coroutineContext[Job]
        requireNotNull(job)
        job.children.toList().associate {
            it.key.toString() to it.isActive
        }
    }
}