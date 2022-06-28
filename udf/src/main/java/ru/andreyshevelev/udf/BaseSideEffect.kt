package ru.andreyshevelev.udf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlin.coroutines.CoroutineContext

abstract class BaseSideEffect<State, Action> {
    abstract fun checkReadiness(action: Action): Boolean
    abstract suspend operator fun invoke(state: State, action: Action): Flow<List<Action>>
    abstract suspend operator fun invoke(t: Throwable): Flow<List<Action>>
    open fun dispatchers(): CoroutineContext = Dispatchers.Default
    suspend fun FlowCollector<List<Action>>.emit(action: Action) {
        emit(listOf(action))
    }
}