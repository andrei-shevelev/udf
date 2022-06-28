package ru.andreyshevelev.udf

abstract class BaseReducer<State, Action, News> {
    private val news: MutableList<News> = mutableListOf()
    operator fun invoke(state: State, action: Action): StoreResult<State, News> {
        val newState = reduce(state, action)
        val storeResult = StoreResult(newState, news.toList())
        news.clear()
        return storeResult
    }

    abstract fun reduce(state: State, action: Action): State

    fun addNews(news: List<News>) {
        this.news.addAll(news)
    }

    fun addNews(news: News) {
        this.news.add(news)
    }
}