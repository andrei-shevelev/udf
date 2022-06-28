package ru.andreyshevelev.udf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlin.coroutines.CoroutineContext

abstract class BaseActionSource<State, Action> {
    private lateinit var stateHolder: StateHolder<State>
    val state: State
        get() = stateHolder.state

    fun setStateHolder(stateHolder: StateHolder<State>) {
        this.stateHolder = stateHolder
    }

    abstract suspend operator fun invoke(): Flow<List<Action>>
    abstract suspend operator fun invoke(t: Throwable): Flow<List<Action>>
    open fun dispatchers(): CoroutineContext = Dispatchers.Default
    suspend fun FlowCollector<List<Action>>.emit(action: Action) {
        emit(listOf(action))
    }
}