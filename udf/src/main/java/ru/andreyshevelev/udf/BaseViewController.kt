package ru.andreyshevelev.udf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

abstract class BaseViewController<State, Action, News>(
    private val store: BaseStore<State, Action, News>
) {

    private var view: BaseView<State, News>? = null
    private var job: Job? = null

    abstract fun scope(): CoroutineScope?

    open fun onForeground(): List<Action> { return listOf() }

    fun onResume(view: BaseView<State, News>) {
        this.view = view
        job = scope()?.launch {
            store.subscribe().collect { storeResult ->
                view.render(storeResult.state, storeResult.news)
            }
        }
        sendAction(onForeground())
    }

    fun onPause() {
        job?.cancel()
    }

    fun onDestroy() {
        job?.cancel()
        store.dispose()
        view = null
    }

    protected fun sendAction(actions: List<Action>) {
        store.sendActions(actions)
    }

    protected fun sendAction(action: Action) {
        store.sendActions(listOf(action))
    }
}