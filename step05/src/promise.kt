import io.vertx.core.Vertx
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Created by mike on 15/11/15.
 */

fun <T> LinkedList<T>.queue(v: T) = addFirst(v)
fun <T> LinkedList<T>.dequeue(): T = removeLast()
fun <T> LinkedList<T>.isNotEmpty() = !isEmpty()


fun promisedVertx(): Vertx {
    val vertx = Vertx.vertx()
    Promise.eventLoop = {
        vertx?.nettyEventLoopGroup()?.schedule(it, 0L, TimeUnit.NANOSECONDS)
    }
    return vertx
}

class Promise<T : Any?>(var parent: Promise<*>?) {
    private var resolved: Boolean = false
    private var resolvedValue: T? = null
    private var resolvedException: Throwable? = null
    private val callbacks = LinkedList<(T) -> Any>()
    private val failcallbacks = LinkedList<(Throwable) -> Any>()


    private constructor(parent: Promise<*>?,value: Throwable):this(null)
    {
        resolved = true
        resolvedException = value
    }

    class Deferred<T : Any> {
        public val promise: Promise<T> = Promise<T>(null)

        public fun resolve(value: T) {
            this.promise.resolve(value)
        }

        public fun progress(value: Double) {
            this.promise.progress(value)
        }

        public fun reject(value: Throwable) {
            this.promise.reject(value);
        }
    }

    companion object {

        var eventLoop: ((() -> Unit) -> Unit)? = null


        fun <T : Any> invoke(callback: (resolve: (value: T) -> Unit, reject: (error: Throwable) -> Unit) -> Unit): Promise<T> {
            val deferred = Deferred<T>()
            callback({ deferred.resolve(it) }, { deferred.reject(it) })
            return deferred.promise
        }

        fun <T : Any> create(callback: (resolve: (value: T) -> Unit, reject: (error: Throwable) -> Unit) -> Unit): Promise<T> {
            val deferred = Deferred<T>()
            callback({ deferred.resolve(it) }, { deferred.reject(it) })
            return deferred.promise
        }

        fun <T : Any> sequence(vararg promises: () -> Promise<T>): Promise<List<T>> {
            return sequence(promises.toList())
        }

        fun <T : Any> sequence(promises: Iterable<() -> Promise<T>>): Promise<List<T>> {
            val items = promises.toLinkedList()
            if (items.size == 0) return Promise.resolved(listOf<T>())
            val out = ArrayList<T>(items.size)
            val deferred = Deferred<List<T>>()
            fun step() {
                if (items.isEmpty()) {
                    deferred.resolve(out)
                } else {
                    val promiseGenerator = items.removeFirst()
                    val promise = promiseGenerator()
                    promise.then {
                        out.add(it)
                        step()
                    }.fail {
                        deferred.reject(it)
                    }
                }
            }
            eventLoop!! { step() }
            return deferred.promise
        }

        fun chain(): Promise<Unit> = resolved(Unit)

        fun <T : Any> resolved(value: T): Promise<T> {
            val deferred = Deferred<T>()
            deferred.resolve(value)
            return deferred.promise
        }

        fun <T : Any> rejected(value: Throwable): Promise<T> {
            return Promise(null,value);
        }

        fun <T : Any> all(vararg promises: Promise<T>): Promise<List<T>> {
            return all(promises.toList())
        }

        fun <T : Any> all(promises: Iterable<Promise<T>>): Promise<List<T>> {
            val promiseList = promises.toList()
            var count = 0
            val total = promiseList.size

            val out = arrayListOf<T?>()
            val deferred = Deferred<List<T>>()
            for (n in 0..total - 1) out.add(null)

            fun checkDone() {
                if (count >= total) {
                    deferred.resolve(out.map { it!! })
                }
            }

            promiseList.indices.forEach {
                val index = it
                val promise = promiseList[index]
                promise.then {
                    out[index] = it
                    count++
                    checkDone()
                }
            }

            checkDone()

            return deferred.promise
        }

        fun <T : Any> forever(): Promise<T> {
            return Deferred<T>().promise
        }

        fun <T : Any> any(vararg promises: Promise<T>): Promise<T> {
            val deferred = Promise.Deferred<T>()
            for (promise in promises) {
                promise.then { deferred.resolve(it) }.fail { deferred.reject(it) }
            }
            return deferred.promise
        }
    }

    internal fun resolve(value: T) {
        if (resolved) return;
        resolved = true
        resolvedValue = value
        parent = null
        flush();
    }

    internal fun reject(value: Throwable) {
        if (resolved) return;
        resolved = true
        resolvedException = value
        parent = null

        // @TODO: Check why this fails!
        if (failcallbacks.isEmpty() && callbacks.isEmpty()) {
            println("Promise.reject(): Not capturated: $value")
            throw value
        }

        flush();
    }

    internal fun progress(value: Double) {

    }

    private fun flush() {
        if (!resolved || (callbacks.isEmpty() && failcallbacks.isEmpty())) return

        val resolvedValue = this.resolvedValue
        if (resolvedValue != null) {
            while (callbacks.isNotEmpty()) {
                val callback = callbacks.dequeue();
                eventLoop!!{
                    callback(resolvedValue)
                }
            }
        } else if (resolvedException != null) {
            while (failcallbacks.isNotEmpty()) {
                val failcallback = failcallbacks.dequeue();
                eventLoop!!{
                    failcallback(resolvedException!!)
                }
            }
        }
    }

    public fun <T2 : Any?> pipe(callback: (value: T) -> Promise<T2>): Promise<T2> {
        try {
            val out = Promise<T2>(this)
            this.failcallbacks.queue {
                out.reject(it)
            }
            this.callbacks.queue({
                callback(it)
                        .then { out.resolve(it) }
                        .fail { out.fail { it } }
            })
            return out
        } finally {
            flush()
        }
    }

    public fun <T2 : Any?> then(callback: (value: T) -> T2): Promise<T2> {
        try {
            val out = Promise<T2>(this)
            this.failcallbacks.queue {
                out.reject(it)
            }
            this.callbacks.queue {
                try {
                    out.resolve(callback(it))
                } catch (t: Throwable) {
                    println("then catch:$t")
                    t.printStackTrace()
                    out.reject(t)
                }
            }
            return out
        } finally {
            flush()
        }
    }

    public fun <T2 : Any?> fail(failcallback: (throwable: Throwable) -> T2): Promise<T2> {
        try {
            val out = Promise<T2>(this)
            this.failcallbacks.queue {
                try {
                    out.resolve(failcallback(it))
                } catch (t: Throwable) {
                    println("fail catch:$t")
                    t.printStackTrace()
                    out.reject(t)
                }
            }
            return out
        } finally {
            flush()
        }
    }

    fun always(callback: () -> Unit): Promise<T> {
        then { callback() }.fail { callback() }
        return this
    }
}
