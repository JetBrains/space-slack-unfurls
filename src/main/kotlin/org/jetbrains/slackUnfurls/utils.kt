package org.jetbrains.slackUnfurls

import org.slf4j.Logger

inline fun <T> List<T>.filterWithLogging(log: Logger, message: String, predicate: (T) -> Boolean): List<T> =
    filter(predicate).also {
        if (it.size < this.size) {
            log.info("Skipping ${this.size - it.size} $message")
        }
    }

inline fun <T, R> List<T>.mapNotNullWithLogging(log: Logger, message: String, transform: (T) -> R?) : List<R> =
    this.mapNotNull(transform).also {
        if (it.size < this.size) {
            log.info("Skipping ${this.size - it.size} $message")
        }
    }
