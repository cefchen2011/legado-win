// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 FlowExtensions.kt（263 行）里最后三个函数 flowWithLifecycleFirst /
// flowWithLifecycleAndDatabaseChange / flowWithLifecycleAndDatabaseChangeFirst
// 依赖 androidx.lifecycle 与 Room 的 invalidationTracker，属于 Android UI 层
// （书架随数据库变化刷新），桌面端由应用外壳用前端事件推送实现。
//
// 这里保留上游**纯协程组合子**的逐字实现，供 JsExtensions 等核心代码使用。
package io.legado.app.utils

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore

inline fun <T, R> Flow<T>.mapIndexed(
    crossinline action: suspend (index: Int, T) -> R,
): Flow<R> = kotlinx.coroutines.flow.flow {
    var index = 0
    collect { value ->
        emit(action(index++, value))
    }
}

inline fun <T, R> Flow<T>.mapAsync(
    concurrency: Int,
    crossinline transform: suspend (T) -> R
): Flow<R> = if (concurrency == 1) {
    map { transform(it) }
} else {
    Semaphore(concurrency).let { semaphore ->
        channelFlow {
            collect {
                semaphore.acquire()
                send(async { transform(it) })
            }
        }.map {
            it.await()
        }.onEach { semaphore.release() }
    }.buffer(0)
}

inline fun <T, R> Flow<T>.mapAsyncIndexed(
    concurrency: Int,
    crossinline transform: suspend (index: Int, T) -> R
): Flow<R> = if (concurrency == 1) {
    mapIndexed { index, value ->
        transform(index, value)
    }
} else {
    Semaphore(concurrency).let { semaphore ->
        channelFlow {
            var index = 0
            collect {
                semaphore.acquire()
                val i = index++
                send(async { transform(i, it) })
            }
        }.map {
            it.await()
        }.onEach { semaphore.release() }
    }.buffer(0)
}

/**
 * 并发映射，单个元素失败不影响整体（上游同名实现）。
 */
inline fun <T, R> Flow<T>.mapParallelSafe(
    concurrency: Int,
    crossinline transform: suspend (T) -> R,
): Flow<R> = flatMapMerge(concurrency) { value ->
    flow {
        try {
            emit(transform(value))
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
        }
    }
}.buffer(0)
