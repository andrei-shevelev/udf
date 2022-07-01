package ru.andreyshevelev.udf

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

abstract class BaseStore<State, Action, News>(
    private var currentState: State,
    private val reducer: BaseReducer<State, Action, News>,
    private val errorHandler: ErrorHandler,
    private val sideEffects: List<BaseSideEffect<State, Action>> = listOf(),
    private val actionSources: List<BaseActionSource<State, Action>> = mutableListOf(),
    private val actionHandlers: List<BaseActionHandler<State, Action>> = listOf(),
    private val bootstrapAction: Action? = null,
    private val storeConfig: StoreConfig = StoreConfig()
) : StateHolder<State> {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val queue: Queue<List<Action>> = LinkedList()

    override val state: State
        get() = currentState

    private val actionFlow: MutableSharedFlow<List<Action>> = MutableSharedFlow(
        extraBufferCapacity = storeConfig.actionBufferSize
    )
    private val stateFlow: MutableSharedFlow<StoreResult<State, News>> = MutableSharedFlow(
        replay = 1,
        extraBufferCapacity = storeConfig.stateBufferSize
    )
    private val childJobs: MutableMap<String, Job> = mutableMapOf()
    private var parentJob: Job? = null

    fun sendActions(actions: List<Action>) {
        actionFlow.tryEmit(actions)
    }

    init {
        parentJob = scope.launch {
            initActionSource()
            actionFlow
                .onStart { bootstrapAction?.let { emit(listOf(it)) } }
                .collect { actions ->
                    actions.forEach { action ->
                        tryCatch(this) {
                            calculateState(action)
                        }
                        initSideEffects(action)
                        initActionHandler(action)
                    }
                }
        }
    }

    fun subscribe(): Flow<StoreResult<State, News>> {
        stateFlow.tryEmit(StoreResult(currentState, listOf()))
        return stateFlow.asSharedFlow()
    }

    fun dispose() {
        childJobs.values.forEach { job -> job.cancel() }
        parentJob?.cancel()
    }

    private fun initActionSource() {
        actionSources.forEach { actionSource ->
            val actionSourcesJob = scope.launch {
                tryCatch(this) {
                    actionSource.setStateHolder(this@BaseStore)
                    withContext(actionSource.dispatchers()) {
                        actionSource()
                            .catch { t ->
                                if (t !is CancellationException) {
                                    errorHandler.handle(t)
                                    actionSource(t)
                                        .collect { actions ->
                                            actionFlow.emit(actions)
                                        }
                                }
                            }
                            .collect { actions ->
                                actionFlow.emit(actions)
                            }
                    }
                }
            }
            childJobs[actionSource::class.java.simpleName] = actionSourcesJob
        }
    }

    private fun initSideEffects(action: Action) {
        sideEffects.forEach { sideEffect ->
            var ready = false
            try {
                ready = sideEffect.checkReadiness(action)
            } catch (t: Throwable) {
                errorHandler.handle(t)
            }
            if (ready) {
                childJobs[sideEffect::class.java.simpleName]?.cancel()
                val sideEffectJob = scope.launch {
                    tryCatch(this) {
                        withContext(sideEffect.dispatchers()) {
                            sideEffect(currentState, action)
                                .catch { t ->
                                    if (t !is CancellationException) {
                                        errorHandler.handle(t)
                                        sideEffect(t)
                                            .collect { actions ->
                                                actionFlow.emit(actions)
                                            }
                                    }
                                }
                                .collect { actions ->
                                    actionFlow.emit(actions)
                                }
                        }
                    }
                }
                childJobs[sideEffect::class.java.simpleName] = sideEffectJob
            }
        }
    }

    private fun initActionHandler(action: Action) {
        actionHandlers.forEach { actionHandler ->
            val actionHandlerJob = scope.launch {
                tryCatch(this) {
                    if (actionHandler.checkReadiness(action)) {
                        withContext(actionHandler.dispatchers()) {
                            try {
                                actionHandler(currentState, action)
                            } catch (t: Throwable) {
                                if (t !is CancellationException) {
                                    errorHandler.handle(t)
                                }
                            }
                        }
                    }
                }
            }
            childJobs[actionHandler::class.java.simpleName] = actionHandlerJob
        }
    }

    private suspend fun calculateState(action: Action) {
        val storeResult = reducer(currentState, action)
        val newState = storeResult.state
        if (newState != currentState || storeResult.news.isNotEmpty()) {
            currentState = newState
            stateFlow.emit(storeResult)
        }
    }

    private suspend fun tryCatch(
        coroutineScope: CoroutineScope,
        lambda: suspend CoroutineScope.() -> Unit
    ) {
        try {
            lambda.invoke(coroutineScope)
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                errorHandler.handle(t)
            }
        }
    }
}