package ru.andreyshevelev.udf

interface ErrorHandler {
    fun handle(t: Throwable)
}