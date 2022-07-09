package ru.andreyshevelev.udf

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
    private var awaitSubscriptionJob: Job? = null
    private var storeResultTmp: StoreResult<State, News> = StoreResult(currentState, listOf())

    fun sendActions(actions: List<Action>) {
        actionFlow.tryEmit(actions)
    }

    init {
        parentJob = scope.launch {
            awaitSubscription()
            actionFlow
                .filter { it.isNotEmpty() }
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
        stateFlow.tryEmit(storeResultTmp)
        return stateFlow.asSharedFlow()
            .map { storeResult ->
                storeResultTmp = storeResultTmp.copy(news = listOf())
                storeResult
            }
    }

    fun dispose() {
        childJobs.values.forEach { job -> job.cancel() }
        parentJob?.cancel()
        awaitSubscriptionJob?.cancel()
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
            if (stateFlow.subscriptionCount.value > 0) {
                storeResultTmp = storeResult
                stateFlow.emit(storeResult)
            } else {
                val mergeNews = mutableListOf<News>().apply {
                    addAll(storeResult.news)
                    addAll(storeResultTmp.news)
                }
                storeResultTmp = storeResultTmp.copy(
                    state = storeResult.state,
                    news = mergeNews
                )
                stateFlow.emit(storeResultTmp)
            }
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

    private fun awaitSubscription() {
        awaitSubscriptionJob = scope.launch {
            actionFlow.subscriptionCount
                .filter { it > 0 }
                .take(1)
                .collect { count ->
                    if (count > 0) {
                        initActionSource()
                    }
                }
        }
    }
}