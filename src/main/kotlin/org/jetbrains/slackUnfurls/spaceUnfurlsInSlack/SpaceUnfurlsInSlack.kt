package org.jetbrains.slackUnfurls.spaceUnfurlsInSlack

import com.slack.api.Slack
import com.slack.api.app_backend.SlackSignature
import com.slack.api.app_backend.events.EventTypeExtractorImpl
import com.slack.api.app_backend.events.payload.AppUninstalledPayload
import com.slack.api.app_backend.events.payload.LinkSharedPayload
import com.slack.api.app_backend.events.payload.TeamDomainChangePayload
import com.slack.api.app_backend.interactive_components.ActionResponseSender
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload
import com.slack.api.app_backend.interactive_components.response.ActionResponse
import com.slack.api.methods.request.chat.ChatUnfurlRequest
import com.slack.api.model.kotlin_extension.block.element.ButtonStyle
import com.slack.api.model.kotlin_extension.block.withBlocks
import com.slack.api.util.json.GsonFactory
import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.locations.*
import io.ktor.request.*
import io.ktor.response.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.slackUnfurls.*
import org.jetbrains.slackUnfurls.html.respondError
import org.jetbrains.slackUnfurls.routing.Routes
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.SlackAppClient
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders.ChatUnfurlProvider
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders.CodeReviewUnfurlProvider
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders.IssueUnfurlProvider
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders.UnfurlProvider
import org.jetbrains.slackUnfurls.storage.SlackUserKey
import org.jetbrains.slackUnfurls.storage.SpaceOrg
import org.jetbrains.slackUnfurls.storage.UserToken
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import space.jetbrains.api.runtime.*


private val slackEventTypeExtractor = EventTypeExtractorImpl()
private val slackSignatureVerifier =
    SlackCredentials.signingSecret?.let { SlackSignature.Verifier(SlackSignature.Generator(it)) }


fun Application.launchSpaceUnfurlsInSlack() {
    launch {
        while (isActive) {
            val payload = processUnfurlsChannel.receive()
            processLinkSharedEvent(payload, locations)
        }
    }
}

suspend fun onSlackEvent(call: ApplicationCall) {
    val timestamp = call.request.header(SlackSignature.HeaderNames.X_SLACK_REQUEST_TIMESTAMP)
    val signature = call.request.header(SlackSignature.HeaderNames.X_SLACK_SIGNATURE)
    if (timestamp == null || signature == null) {
        call.respondError(
            HttpStatusCode.BadRequest,
            log,
            "HTTP headers ${SlackSignature.HeaderNames.X_SLACK_REQUEST_TIMESTAMP} and ${SlackSignature.HeaderNames.X_SLACK_SIGNATURE} are required"
        )
        return
    }
    val requestBody = call.receiveText()
    if (slackSignatureVerifier?.isValid(timestamp, requestBody, signature) == false) {
        call.respondError(HttpStatusCode.BadRequest, log, "Invalid request signature")
        return
    }

    val requestBodyJson = Json.parseToJsonElement(requestBody)
    when (val payloadType = requestBodyJson.jsonObject["type"]?.jsonPrimitive?.content) {
        "url_verification" -> {
            val challenge = requestBodyJson.jsonObject["challenge"]?.jsonPrimitive?.content
            challenge?.let { call.respondText(it) }
                ?: call.respondError(HttpStatusCode.BadRequest, log, "Challenge expected in url verification request")
            return
        }
        "event_callback" ->
            when (slackEventTypeExtractor.extractEventType(requestBody)) {
                "link_shared" ->
                    processUnfurlsChannel.send(gson.fromJson(requestBody, LinkSharedPayload::class.java))
                "team_domain_change" -> {
                    val evt = gson.fromJson(requestBody, TeamDomainChangePayload::class.java)
                    db.slackTeams.updateDomain(evt.teamId, evt.event.domain)
                }
                "app_uninstalled" -> {
                    val evt = gson.fromJson(requestBody, AppUninstalledPayload::class.java)
                    db.slackTeams.delete(evt.teamId)
                }
                else ->
                    log.warn("Unprocessed Slack event type - $payloadType")
            }
        else ->
            log.warn("Unexpected Slack event payload type - $payloadType")
    }
    call.respond(HttpStatusCode.OK)
}

suspend fun onSlackInteractive(call: ApplicationCall) {
    val payloadJson = call.receiveParameters()["payload"]
        ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                log,
                "Expected form parameter payload in interactive payload request body"
            )
            return
        }

    val payload = gson.fromJson(payloadJson, BlockActionPayload::class.java)
    val action = payload.actions.singleOrNull() ?: run {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected a single action in block_actions payload")
        return
    }

    when (action.actionId) {
        AuthAction.Authenticate.id, AuthAction.NotNow.id -> {
            ActionResponseSender(Slack.getInstance()).send(
                payload.responseUrl,
                ActionResponse.builder().deleteOriginal(true).build()
            )
        }
        AuthAction.Never.id -> {
            disableUnfurling(payload, spaceOrgId = action.value)
        }
    }

    call.respond(HttpStatusCode.OK)
}

suspend fun processDeferredLinkSharedEvents(slackTeamId: String, slackUserId: String, spaceOrgId: String) {
    withSlackLogContext(slackTeamId, slackUserId, spaceOrgId) {
        db.deferredSlackLinkUnfurlEvents
            .getOnce(slackTeamId = slackTeamId, slackUserId = slackUserId, spaceOrgId = spaceOrgId, limit = 10)
            .also {
                log.info("Enqueued ${it.size} deferred link unfurl events from Slack to process after user authenticated in Space")
            }
            .forEach {
                processUnfurlsChannel.send(gson.fromJson(it, LinkSharedPayload::class.java))
            }
    }
}

private sealed class LinkToUnfurl {

    class ReadyToUnfurl(
        val originalLink: String,
        val url: Url,
        val spaceOrg: SpaceOrg,
        private val spaceClient: SpaceClient,
        private val matchResult: MatchResult,
        private val provide: UnfurlProvider
    ) {
        suspend fun provideUnfurlDetail(): ChatUnfurlRequest.UnfurlDetail? {
            return provide(url, matchResult, spaceClient)
        }
    }

    // link points to some Space url that link previews aren't supported for
    object NotSupportedEntity

    class UserAuthRequired(val spaceOrg: SpaceOrg)

    object UnfurlsDisabledByUser
}

private suspend fun processLinkSharedEvent(payload: LinkSharedPayload, locations: Locations) {
    val slackTeamId = payload.teamId
    val slackUserId = payload.event.user
    withSlackLogContext(slackTeamId, slackUserId, spaceOrgId = "") {
        val event = payload.event
        val slackAppClient = SlackAppClient.tryCreate(slackTeamId, log)
        if (slackAppClient == null) {
            log.warn("Application is not installed into Slack team")
            return@withSlackLogContext
        }

        val linksToUnfurl = event.links
            .map { it.url to Url(it.url.replace("&amp;", "&")) }
            .mapNotNullWithLogging(log, message = "links because application is not installed to Space org") { (link, url) ->
                db.spaceOrgs.getByDomain(url.host)?.let { Triple(link, url, it) }
            }
            .map { (link, url, spaceOrg) ->
                val (matchResult, provide) = spaceUnfurlProviders.flatMap { it.matchers }
                    .firstNotNullOfOrNull { (regex, provide) ->
                        regex.matchEntire(url.encodedPath)?.let { it to provide }
                    }
                    ?: run {
                        log.info("Space url ${url.encodedPath} is not recognized for link preview")
                        return@map LinkToUnfurl.NotSupportedEntity
                    }

                val key =
                    SlackUserKey(slackTeamId = slackTeamId, slackUserId = slackUserId, spaceOrgId = spaceOrg.clientId)
                when (val spaceUserToken = db.spaceUserTokens.get(key)) {
                    is UserToken.Value -> {
                        val spaceClient = getSpaceClient(spaceOrg, spaceUserToken)
                        LinkToUnfurl.ReadyToUnfurl(link, url, spaceOrg, spaceClient, matchResult, provide)
                    }
                    is UserToken.UnfurlsDisabled ->
                        LinkToUnfurl.UnfurlsDisabledByUser
                    null ->
                        LinkToUnfurl.UserAuthRequired(spaceOrg)
                }
            }
            .filterWithLogging(log, message = "links because unfurls have been disabled by user") {
                it !is LinkToUnfurl.UnfurlsDisabledByUser
            }
            .filterWithLogging(log, message = "links because they do not match unfurlable Space entities") {
                it !is LinkToUnfurl.NotSupportedEntity
            }

        val readyToUnfurl = linksToUnfurl.filterIsInstance<LinkToUnfurl.ReadyToUnfurl>()
        val needRequestAuth = linksToUnfurl.filterIsInstance<LinkToUnfurl.UserAuthRequired>()

        // prompt user to pass authentication in Space if all Space links in the message require authentication
        // but go straight to unfurling links if we can unfurl at least one of them
        if (readyToUnfurl.isEmpty() && needRequestAuth.isNotEmpty()) {
            val spaceOrg = needRequestAuth.first().spaceOrg
            val spaceOAuthUrl = "$entrypointUrl/${
                locations.href(
                    Routes.SpaceOAuth(
                        slackTeamId = slackTeamId,
                        slackUserId = slackUserId,
                        spaceOrgId = spaceOrg.clientId
                    )
                )
            }"
            slackAppClient.sendUnfurlsToSlack {
                it.unfurlId(event.unfurlId)
                it.source(event.source)
                it.userAuthBlocks(authRequestMessage(spaceOrg, spaceOAuthUrl))
            }

            db.deferredSlackLinkUnfurlEvents.create(
                slackTeamId = slackTeamId,
                slackUserId = slackUserId,
                spaceOrgId = spaceOrg.clientId,
                event = gson.toJson(payload)
            )
            return@withSlackLogContext
        }

        // generate unfurls
        val unfurls = readyToUnfurl.mapNotNull { item ->
            val spaceOrgId = item.spaceOrg.clientId
            withSlackLogContext(slackTeamId, slackUserId, spaceOrgId) {
                log.info("Providing unfurls for a link")
                try {
                    item.provideUnfurlDetail()?.let { item.originalLink to it }
                } catch (ex: Exception) {
                    val errorDetails = if (ex is RequestException) {
                        val responseJson = ex.response.readText(Charsets.UTF_8).let(::parseJson)
                        val message = responseJson?.let {
                            val error = it.get("error")?.takeIf { it.isTextual }?.asText()
                            val errorDescription = it.get("error_description")?.takeIf { it.isTextual }?.asText()
                            "Error = '$error', description = '$errorDescription'"
                        }.orEmpty()
                        if (ex is AuthenticationRequiredException) {
                            db.spaceUserTokens.delete(
                                slackTeamId = slackTeamId,
                                slackUserId = slackUserId,
                                spaceOrgId = spaceOrgId
                            )
                            "Dropped user refresh token for Space. $message"
                        } else message
                    } else ""

                    log.error("Error providing unfurl. $errorDetails", ex)
                    null
                }
            }
        }

        if (unfurls.isNotEmpty()) {
            slackAppClient.sendUnfurlsToSlack {
                it.unfurlId(event.unfurlId)
                it.source(event.source)
                it.unfurls(unfurls.associate { it })
            }
        }
    }
}

private fun authRequestMessage(spaceOrg: SpaceOrg, spaceOAuthUrl: String) = withBlocks {
    section {
        markdownText("Authenticate in ${spaceOrg.domain} to get link previews in Slack")
    }
    actions {
        elements {
            button {
                text("Authenticate")
                actionId(AuthAction.Authenticate.id)
                value(spaceOrg.domain)
                url(spaceOAuthUrl)
                style(ButtonStyle.PRIMARY)
            }
            button {
                text("Not now")
                actionId(AuthAction.NotNow.id)
                value(spaceOrg.clientId)
            }
            button {
                text("Never ask me again")
                actionId(AuthAction.Never.id)
                value(spaceOrg.clientId)
            }
        }
    }
}

private suspend fun disableUnfurling(payload: BlockActionPayload, spaceOrgId: String) {
    withSlackLogContext(payload.team.id, payload.user.id, spaceOrgId) {
        db.spaceUserTokens.disableUnfurls(
            slackTeamId = payload.team.id,
            slackUserId = payload.user.id,
            spaceOrgId = spaceOrgId
        )
        ActionResponseSender(Slack.getInstance()).send(
            payload.responseUrl,
            ActionResponse.builder().deleteOriginal(true).build()
        )
        log.info("Disabled Space links unfurling")
    }
}


private val spaceHttpClient = ktorClientForSpace()

/** Gets cached Space client with refresh token authentication on behalf of the user */
private fun getSpaceClient(spaceOrg: SpaceOrg, userTokens: UserToken.Value): SpaceClient {
    val spaceAppInstance = SpaceAppInstance(
        clientId = spaceOrg.clientId,
        clientSecret = decrypt(spaceOrg.clientSecret),
        spaceServerUrl = spaceOrg.url
    )
    // TODO - somehow hook into the token refresh process and save new refresh token to DB in case Space issues a new one
    return SpaceClient(
        spaceHttpClient,
        spaceAppInstance,
        SpaceAuth.RefreshToken(decrypt(userTokens.refreshToken), spaceUserPermissionScopes.joinToString(" "))
    )
}


val spaceUnfurlProviders = listOf(
    IssueUnfurlProvider,
    CodeReviewUnfurlProvider,
    ChatUnfurlProvider
)

private val processUnfurlsChannel = Channel<LinkSharedPayload>(Channel.BUFFERED)

/** Permissions for the application token in Slack required for listening to unfurl requests and providing unfurls content */
val slackAppPermissionScopes = listOf(
    "links:read",
    "links:write",
    "team:read",
    "chat:write"
)

/** Slack error codes that cause Slack refresh token reset and repeated request for the user to authenticate in Slack */
val slackErrorsToResetToken = listOf(
    "invalid_auth",
    "account_inactive",
    "no_permission",
    "missing_scope",
    "not_allowed_token_type",
    "cannot_find_service"
)

private val gson = GsonFactory.createSnakeCase()

private val log: Logger = LoggerFactory.getLogger("SpaceUnfurlsInSlack")
