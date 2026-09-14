package com.ninecsdev.wallpaperchanger.logic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** [map], with at most [limit] calls to [transform] running at a time. It preserves order.*/
suspend fun <T, R> Iterable<T>.mapConcurrently(
    limit: Int,
    transform: suspend (T) -> R
): List<R> = coroutineScope {
    val gate = Semaphore(limit)
    map { item -> async { gate.withPermit { transform(item) } } }.awaitAll()
}