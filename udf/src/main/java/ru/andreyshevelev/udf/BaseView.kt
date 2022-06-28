package ru.andreyshevelev.udf

interface BaseView<State, News> {
    fun render(state: State, listNews: List<News>)
}