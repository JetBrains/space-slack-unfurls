package org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders

import com.slack.api.methods.request.chat.ChatUnfurlRequest
import com.slack.api.model.kotlin_extension.block.BlockLayoutBuilder
import com.slack.api.model.kotlin_extension.block.dsl.ContextBlockElementDsl
import io.ktor.http.*
import org.jetbrains.slackUnfurls.entrypointUrl
import space.jetbrains.api.runtime.SpaceClient


typealias UnfurlProvider = suspend (
    url: Url,
    match: MatchResult,
    spaceClient: SpaceClient
) -> ChatUnfurlRequest.UnfurlDetail?

interface SpaceUnfurlProvider {
    val matchers: List<Pair<Regex, UnfurlProvider>>
    val spacePermissionScopes: List<String>
}


val PROJECT_KEY_REGEX by lazy {
    Regex("([A-Z](?:(?:[_-][A-Z0-9])|[A-Z0-9])+)")
}

@BlockLayoutBuilder
fun ContextBlockElementDsl.spaceLogo() =
    image("$entrypointUrl/static/space.jpeg", "Space Logo")

fun String.limitTo(limit: Int = 255) =
    if (length <= limit) {
        this
    }
    else {
        substring(0, limit - 1) + '…'
    }
