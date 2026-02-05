package cn.edu.buaa.scs.utils.schedule

import cn.edu.buaa.scs.error.TimeoutException
import cn.edu.buaa.scs.utils.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList

object CommonScheduler {
    private val defaultDispatcher = Dispatchers.Default

    suspend fun <T> multiCoroutinesProduceSync(
        actionList: List<suspend () -> T>,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): List<T> =
        withContext(defaultDispatcher) {
            val output = Channel<T>(actionList.size)
            val jobs = mutableListOf<Job>()
            actionList.forEach { action ->
                jobs += launch(dispatcher) {
                    output.send(action.invoke())
                }
            }
            jobs.forEach { it.join() }
            output.close()
            output.toList()
        }
}

suspend fun waitForDone(timeout: Long, interval: Long = 100L, check: suspend () -> Boolean): Result<Unit> {
    return try {
        withTimeout(timeout) {
            while (!check.invoke()) {
                delay(interval)
            }
        }
        Result.success(Unit)
    } catch (e: TimeoutCancellationException) {
        Result.failure(TimeoutException(e.message ?: "Timeout for $timeout ms"))
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

suspend fun <T, R> List<T>.forEachAsync(action: suspend (T) -> R): List<R> {
    return CommonScheduler.multiCoroutinesProduceSync(map { { action.invoke(it) } })
}

suspend fun retry(maxRetry: Int, check: suspend () -> Unit): Result<Unit> {
    val retryLogger = logger("retry")()
    var retried = 0
    var ex: Throwable? = null
    while (retried < maxRetry) {
        try {
            check()
            return Result.success(Unit)
        } catch (e: Throwable) {
            retried++
            if (retried < maxRetry) {
                retryLogger.warn("task failed, retry {}/{}", retried + 1, maxRetry)
            }
            ex = e
        }
    }

    retryLogger.error("task failed after {} retries. last error:", maxRetry)
    retryLogger.error("exception", ex)
    return Result.failure(ex!!)
}