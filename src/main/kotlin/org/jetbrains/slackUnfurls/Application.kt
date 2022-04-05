package org.jetbrains.slackUnfurls

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.ktor.application.*
import org.jetbrains.slackUnfurls.routing.configureRouting
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.launchSlackUnfurlsInSpace
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.launchSpaceUnfurlsInSlack
import org.jetbrains.slackUnfurls.storage.Storage
import org.jetbrains.slackUnfurls.storage.postgres.initPostgres


@Suppress("unused")
fun Application.module() {
    db
    configureRouting()
    launchSlackUnfurlsInSpace()
    launchSpaceUnfurlsInSlack()
}

val db: Storage by lazy {
    initPostgres()
        ?: error("Should specify connection config parameters for either DynamoDB or PostgreSQL")
}

val config: Config = ConfigFactory.load()

val entrypointUrl =
    config.getString("app.entrypointUrl").trimEnd('/').ifBlank { error("Entrypoint url should not be empty") }

object SlackCredentials {
    val clientId: String = config.getString("slack.clientId").ifBlank { error("Slack client id should not be empty") }
    val clientSecret: String = config.getString("slack.clientSecret").ifBlank { error("Slack client secret should not be empty") }
    val signingSecret: String? = config.getString("slack.signingSecret").ifBlank { null }
}
