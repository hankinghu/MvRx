package com.airbnb.mvrx

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import java.util.concurrent.Executors

@Suppress("EXPERIMENTAL_API_USAGE")
class CoroutinesStateStore<S : MvRxState>(
    initialState: S,
    scope: CoroutineScope
) : MvRxStateStore<S> {

    private val setStateChannel = Channel<S.() -> S>(capacity = Channel.UNLIMITED)
    private val withStateChannel = Channel<(S) -> Unit>(capacity = Channel.UNLIMITED)

    /**
     * This combination of BroadcastChannel, mutable state property and flow was arrived
     * at after multiple attempts. This code needs to:
     * 1) Multicast
     * 2) Never fail to deliver an intermediate state to subscribers even if updates are fast or
     *    some subscribers are slow.
     * 3) Be able to provide an initial value.
     *
     * StateFlow can't be used because it conflates new sets. If you set its value in a tight
     * loop and listen to changes on a flow, you will miss values. ConflatedBroadcastChannel
     * alone has the same issue.
     *
     * A normal Channel can't be used because it isn't multicast.
     */
    private val stateChannel = BroadcastChannel<S>(capacity = Channel.BUFFERED)
    override var state = initialState

    /**
     * Returns a [Flow] for this store's state. It will begin by immediately emitting
     * the latest set value and then continue with all subsequent updates.
     *
     * This Flow has a buffer size of 1 to ensure that the channel is subscribed to immediately.
     */
    override val flow: Flow<S>
        get() = flow {
            emit(state)
            stateChannel.consumeEach { emit(it) }
        }.buffer(1)

    init {
        setupTriggerFlushQueues(scope)
        scope.coroutineContext[Job]!!.invokeOnCompletion {
            closeChannels()
        }
    }

    /**
     * Poll [withStateChannel] and [setStateChannel] to respond to set/get state requests.
     */
    private fun setupTriggerFlushQueues(scope: CoroutineScope) {
        scope.launch(flushDispatcherOverride ?: flushDispatcher) {
            try {
                while (isActive) {
                    flushQueuesOnce()
                }
            } finally {
                closeChannels()
            }
        }
    }

    /**
     * Close all channels. It will complete state flow as well.
     */
    private fun closeChannels() {
        stateChannel.close()
        setStateChannel.close()
        withStateChannel.close()
    }

    /**
     * Flush the setState and withState queues.
     * All pending setState reducers will be run prior to every single withState lambda.
     * This ensures that situations such as the following will work correctly:
     *
     * Situation 1
     *
     * setState { ... }
     * withState { ... }
     *
     * Situation 2
     *
     * withState {
     *     setState { ... }
     *     withState { ... }
     * }
     */
    private suspend fun flushQueuesOnce() {
        select<Unit> {
            setStateChannel.onReceive { reducer ->
                val newState = state.reducer()
                if (newState != state) {
                    stateChannel.send(newState)
                    state = newState
                }
            }
            withStateChannel.onReceive { block ->
                block(state)
            }
        }
    }

    override fun get(block: (S) -> Unit) {
        withStateChannel.offer(block)
    }

    override fun set(stateReducer: S.() -> S) {
        setStateChannel.offer(stateReducer)
    }

    companion object {
        internal var flushDispatcherOverride: CoroutineDispatcher? = null
        private val flushDispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    }
}