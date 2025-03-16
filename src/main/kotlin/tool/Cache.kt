package com.example.tool

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

interface Cache<KT, VT> {
    suspend operator fun get(key: KT): VT

    companion object {
        class MemCache<KT, VT> (
            private val duration: Duration = Duration.INFINITE,
            private val getFunction: suspend (KT) -> VT,
        ) : Cache<KT, VT> {
            private val data = mutableMapOf<KT, Pair<VT, Instant>>()

            override suspend operator fun get(key: KT): VT {
                data[key]?.let { (value, expireTime) ->
                    if (expireTime > Clock.System.now()) {
                        return@get value
                    } else {
                        data.remove(key)
                    }
                }
                val value = getFunction(key)
                data[key] = value to Clock.System.now() + duration
                return value
            }
        }
    }
}