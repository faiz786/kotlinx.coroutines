/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental.android

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.experimental.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Dispatches execution onto Android main UI thread and provides native [delay][Delay.delay] support.
 */
val UI = HandlerContext(Handler(Looper.getMainLooper()), "UI")

/**
 * Represents an arbitrary [Handler] as a implementation of [CoroutineDispatcher].
 */
fun Handler.asCoroutineDispatcher() = HandlerContext(this)

private const val MAX_DELAY = Long.MAX_VALUE / 2 // cannot delay for too long on Android

/**
 * Implements [CoroutineDispatcher] on top of an arbitrary Android [Handler].
 * @param handler a handler.
 * @param name an optional name for debugging.
 */
public class HandlerContext(
    private val handler: Handler,
    private val name: String? = null
) : CoroutineDispatcher(), Delay {
    @Volatile
    private var _choreographer: Choreographer? = null

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) {
        handler.postDelayed({
            with(continuation) { resumeUndispatched(Unit) }
        }, unit.toMillis(time).coerceAtMost(MAX_DELAY))
    }

    override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle {
        handler.postDelayed(block, unit.toMillis(time).coerceAtMost(MAX_DELAY))
        return object : DisposableHandle {
            override fun dispose() {
                handler.removeCallbacks(block)
            }
        }
    }

    /**
     * Awaits the next animation frame and returns frame time in nanoseconds.
     */
    public suspend fun awaitFrame(): Long {
        // fast path when choreographer is already known
        val choreographer = _choreographer
        if (choreographer != null) {
            return suspendCancellableCoroutine { cont ->
                postFrameCallback(choreographer, cont)
            }
        }
        // post into looper thread thread to figure it out
        return suspendCancellableCoroutine { cont ->
           handler.post {
               updateChoreographerAndPostFrameCallback(cont)
           }
        }
    }

    private fun updateChoreographerAndPostFrameCallback(cont: CancellableContinuation<Long>) {
        val choreographer = _choreographer ?:
            Choreographer.getInstance()!!.also { _choreographer = it }
        postFrameCallback(choreographer, cont)
    }

    private fun postFrameCallback(choreographer: Choreographer, cont: CancellableContinuation<Long>) {
        choreographer.postFrameCallback { nanos ->
            with(cont) { resumeUndispatched(nanos) }
        }
    }

    override fun toString() = name ?: handler.toString()
    override fun equals(other: Any?): Boolean = other is HandlerContext && other.handler === handler
    override fun hashCode(): Int = System.identityHashCode(handler)
}
