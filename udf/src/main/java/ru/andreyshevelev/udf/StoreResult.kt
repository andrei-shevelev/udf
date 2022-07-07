package ru.andreyshevelev.udf

data class StoreResult<State, News>(val state: State, val news: List<News>)