package ru.andreyshevelev.udf

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

abstract class BaseActionHandler<State, Action> {
    abstract fun checkReadiness(action: Action): Boolean
    abstract suspend operator fun invoke(state: State, action: Action)
    open fun dispatchers(): CoroutineContext = Dispatchers.Default
}