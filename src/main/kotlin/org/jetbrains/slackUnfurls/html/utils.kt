package org.jetbrains.slackUnfurls.html

import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.http.*
import kotlinx.html.*
import org.slf4j.Logger


fun HTML.errorPage(message: String) = page {
    h3 { +"Something went wrong..." }

    div {
        classes = setOf("error")
        +message
    }
}

fun HTML.successPage(message: String) = page {
    div {
        classes = setOf("success")
        +message
    }
}

fun HTML.page(initHead: HEAD.() -> Unit = {}, initBody: DIV.() -> Unit) {
    head {
        styleLink("/static/styles.css")
        initHead()
    }
    body {
        div {
            classes = setOf("main")

            h2 {
                +"Where JetBrains Space meets Slack"
            }

            initBody()
        }
    }
}

suspend fun ApplicationCall.respondError(statusCode: HttpStatusCode, log: Logger, message: String) {
    log.warn(message)
    respondHtml(statusCode) {
        errorPage(message)
    }
}

suspend fun ApplicationCall.respondSuccess(log: Logger, message: String) {
    log.info(message)
    respondHtml {
        successPage(message)
    }
}
