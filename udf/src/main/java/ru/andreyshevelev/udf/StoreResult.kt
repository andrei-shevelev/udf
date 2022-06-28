package ru.andreyshevelev.udf

class StoreResult<State, News>(val state: State, val news: List<News>)