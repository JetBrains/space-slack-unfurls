package org.jetbrains.slackUnfurls.slackUnfurlsInSpace

import com.slack.api.model.Message
import com.slack.api.model.block.RichTextBlock
import com.slack.api.model.block.element.*
import com.slack.api.model.block.element.RichTextSectionElement.TextStyle
import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.locations.*
import io.ktor.request.*
import io.ktor.response.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.slackUnfurls.*
import org.jetbrains.slackUnfurls.routing.Routes
import org.jetbrains.slackUnfurls.storage.SpaceOrg
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import space.jetbrains.api.runtime.*
import space.jetbrains.api.runtime.helpers.*
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.teamDirectory
import space.jetbrains.api.runtime.resources.uploads
import space.jetbrains.api.runtime.types.*
import java.net.URI
import java.time.format.DateTimeFormatter


fun Application.launchSlackUnfurlsInSpace() {
    // listen for  completed user authentication flows
    launch {
        while (isActive) {
            val item = processUnfurlsAfterAuthChannel.receive()
            withSpaceLogContext(item.spaceOrgId, item.spaceUserId, "") {
                log.info("User has passed authentication, cleaning up authentication requests")
                kotlin.runCatching {
                    val org = db.spaceOrgs.getById(item.spaceOrgId)
                        ?: error("Integration for Space org hasn't been set up. Reinstall the Space application.")

                    getSpaceClient(org).applications.unfurls.queue.clearExternalSystemAuthenticationRequests(
                        ProfileIdentifier.Id(item.spaceUserId)
                    )
                    scheduleProcessing(org.clientId)
                }.onFailure {
                    log.error("Error processing completed auth flow", it)
                }
            }
        }
    }

    // listen for new unfurl queue items
    launch {
        while (isActive) {
            val clientId = processUnfurlsChannel.receive()
            log.info("Processing new unfurl queue items for Space instance with clientId = $clientId after receiving the webhook call")
            runCatching {
                processUnfurlQueue(clientId, locations)
            }.onFailure {
                log.error("Error processing unfurl queue for Space client id $clientId", it)
            }
        }
    }
}

suspend fun onSpaceCall(call: ApplicationCall) {

    val requestAdapter =  object : RequestAdapter {
        override suspend fun receiveText() =
            call.receiveText()

        override fun getHeader(headerName: String) =
            call.request.header(headerName)

        override suspend fun respond(httpStatusCode: Int, body: String) =
            call.respond(HttpStatusCode.fromValue(httpStatusCode), body)
    }

    Space.processPayload(
        requestAdapter, spaceHttpClient, spaceAppInstanceStorage,
        onAuthFailed = {
            log.warn("Space request authentication failed - $it")
            SpaceHttpResponse.RespondWithCode(HttpStatusCode.BadRequest)
        },
        payloadProcessor = { payload ->
            when (payload) {
                is InitPayload -> {
                    onAppInstalledToSpaceOrg(clientWithClientCredentials())
                    SpaceHttpResponse.RespondWithOk
                }

                is UnfurlActionPayload -> {
                    when (payload.actionId) {
                        AuthAction.NotNow.id -> {
                            clientWithClientCredentials().applications.unfurls.queue.clearExternalSystemAuthenticationRequests(
                                ProfileIdentifier.Id(payload.userId)
                            )
                        }
                        AuthAction.Never.id -> {
                            clientWithClientCredentials().applications.unfurls.queue.clearExternalSystemAuthenticationRequests(
                                ProfileIdentifier.Id(payload.userId)
                            )
                            disableUnfurling(payload.clientId, payload.userId, payload.actionValue)
                        }
                        else -> {
                            log.warn("Unexpected unfurl action - ${payload.actionId}. Space client id ${payload.clientId}")
                        }
                    }
                    SpaceHttpResponse.RespondWithOk
                }

                is NewUnfurlQueueItemsPayload -> {
                    scheduleProcessing(payload.clientId)
                    SpaceHttpResponse.RespondWithOk
                }

                else -> {
                    log.info("Processed payload type ${payload::class.simpleName}")
                    SpaceHttpResponse.RespondWithOk
                }
            }
        }
    )
}

suspend fun ProcessingScope.onAppInstalledToSpaceOrg(spaceClient: SpaceClient) {
    val provideUnfurlsRightCode = "Unfurl.App.ProvideAttachment"
    with(spaceClient.applications.authorizations.authorizedRights) {
        requestRights(
            ApplicationIdentifier.Me,
            GlobalPermissionContextIdentifier,
            listOf(provideUnfurlsRightCode)
        )
    }
    spaceClient.applications.unfurls.domains.updateUnfurledDomains(listOf("slack.com"))

    val resourcePath = "static/slack.jpeg"
    val inputStream =
        this::class.java.classLoader.getResourceAsStream(resourcePath) ?: error("Could not read resource $resourcePath")
    val imageBytes = inputStream.use { it.readBytes() }
    val uploadPath = spaceClient.uploads.createUpload("file")
    val token = spaceClient.auth.token(spaceClient.ktorClient, spaceClient.appInstance)
    val appLogoAttachmentId = spaceClient.ktorClient.put<String>("${spaceClient.server.serverUrl}$uploadPath/slack.jpeg") {
        body = ByteArrayContent(imageBytes)
        header(HttpHeaders.Authorization, "Bearer $token")
    }
    spaceClient.applications.updateApplication(ApplicationIdentifier.Me, pictureAttachmentId = Option.Value(appLogoAttachmentId))
}

suspend fun scheduleProcessing(clientId: String) {
    processUnfurlsChannel.send(clientId)
}

private suspend fun processUnfurlQueue(spaceClientId: String, locations: Locations) {
    withContext(MDCContext(mapOf(MDCParams.SPACE_ORG to spaceClientId))) {
        val spaceOrg = db.spaceOrgs.getById(spaceClientId)
            ?: error("Space instance with client id = $spaceClientId not found, reinstall the Space application")

        var lastEtag: Long? = spaceOrg.lastUnfurlQueueItemEtag
        log.info("Last unfurl queue item etag is $lastEtag")

        val spaceClient = getSpaceClient(spaceOrg)

        var queueItems =
            spaceClient.applications.unfurls.queue.getUnfurlQueueItems(lastEtag, UNFURL_QUEUE_ITEMS_BATCH_SIZE)
        log.info("Fetched ${queueItems.size} unfurl queue items")
        while (queueItems.isNotEmpty()) {
            queueItems
                .filterWithLogging(log, message = "unfurl queue items because they do not have Space author id") { item ->
                    item.authorUserId != null
                }
                .map { Url(it.target) to it }
                .filterWithLogging(log, message = "unfurl queue items because these aren't message links") { (url, _) ->
                    url.fullPath.startsWith("/archives")
                }
                .mapNotNullWithLogging(log, message = "unfurl queue items because application is not installed to Slack workspace") { (url, item) ->
                    db.slackTeams.getByDomain(url.host.removeSuffix(".slack.com"))?.let { it to item }
                }
                .groupBy({ it.first to it.second.authorUserId }, { it.second })
                .forEach { (key, itemsForSlackTeamAndUser) ->
                    val (slackTeam, spaceProfileId) = key
                    val spaceUserId = spaceProfileId?.getUserId(spaceClient)
                    if (spaceUserId == null) {
                        log.warn("User not found by profile id $spaceProfileId")
                        return@withContext
                    }

                    withSpaceLogContext(spaceOrg.clientId, spaceUserId, slackTeam.id) {
                        val context = SpaceUserKey(spaceOrg.clientId, spaceUserId, slackTeam.id)

                        when (val slackClient = SlackUserClient.tryCreate(context, log)) {
                            is SlackUserClient.Instance -> {
                                log.info("Providing unfurls content for ${itemsForSlackTeamAndUser.size} queue items")
                                provideUnfurlsContent(
                                    spaceClient,
                                    slackClient.instance,
                                    slackTeam.domain,
                                    itemsForSlackTeamAndUser
                                )
                            }
                            is SlackUserClient.UnfurlsDisabled -> {
                                log.info("Unfurls disabled by the user")
                            }
                            null -> {
                                requestAuth(context, queueItems, spaceClient, locations)
                            }
                        }
                    }
                }

            lastEtag = queueItems.last().etag
            db.spaceOrgs.updateLastUnfurlQueueItemEtag(spaceOrg.clientId, lastEtag)
            queueItems =
                spaceClient.applications.unfurls.queue.getUnfurlQueueItems(lastEtag, UNFURL_QUEUE_ITEMS_BATCH_SIZE)
            log.info("Fetched ${queueItems.size} unfurl queue items")
        }
    }
}

private suspend fun provideUnfurlsContent(
    spaceClient: SpaceClient,
    slackClient: SlackUserClientImpl,
    slackDomain: String,
    queueItems: List<ApplicationUnfurlQueueItem>
) {
    data class Item(
        val item: ApplicationUnfurlQueueItem,
        val channelId: String,
        val messageId: String,
        val threadTs: String?
    )

    val validItems = queueItems.mapNotNull { item ->
        URI(item.target).let {
            val parts = it.path.split('/').dropWhile { it != "archives" }.drop(1)
            val channelId = parts.firstOrNull()
            val messageId = parts.drop(1).firstOrNull()
            if (channelId != null && messageId != null) {
                val threadTs = it.parseQueryParams()
                    .firstNotNullOfOrNull { (key, value) -> value.takeIf { key == "thread_ts" } }
                Item(item, channelId, messageId, threadTs)
            } else
                null
        }
    }
    log.info("Found ${validItems.size} valid unfurl queue items")

    val unfurls = validItems.mapNotNull { (item, channelId, messageId, threadTs) ->
        val message =
            if (threadTs != null)
                slackClient.fetchThreadMessage(channelId, messageId, threadTs)?.messages?.singleOrNull()
            else
                slackClient.fetchMessage(channelId, messageId)?.messages?.singleOrNull()
        if (message != null) {
            val channelLink =
                if (threadTs != null)
                    "https://$slackDomain.slack.com/archives/$channelId/${tsToMessageId(threadTs)}"
                else
                    slackClient.fetchChannelName(channelId)?.channelLink(slackClient, slackDomain, channelId) ?: channelId
            val userName = slackClient.fetchUserName(message.user)?.userName() ?: message.user
            // TODO - use timestamp MC inline element
            val messageTs = messageId.removePrefix("p").dropLast(6).toLongOrNull()?.let {
                Instant.fromEpochSeconds(it)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .toJavaLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
            val decoratedMessageText = buildString { buildMessageText(message, slackClient, slackDomain) }
            val content = unfurl {
                outline(
                    MessageOutlineLegacy(
                        ApiIcon("slack"),
                        "*$userName* in $channelLink ($messageTs)"
                    )
                )
                section {
                    text(decoratedMessageText)
                    text("[View message](${item.target})")
                }
            }
            ApplicationUnfurl(item.id, content)
        } else {
            log.warn("Failed to fetch message for queue item ${item.id}")
            null
        }
    }

    if (unfurls.isNotEmpty()) {
        spaceClient.applications.unfurls.queue.postUnfurlsContent(unfurls)
    }
}

private suspend fun StringBuilder.buildMessageText(message: Message, slackClient: SlackUserClientImpl, slackDomain: String) {
    message.blocks.filterIsInstance<RichTextBlock>().takeUnless { it.isEmpty() }
        ?.flatMap { it.elements }
        ?.filterIsInstance<RichTextElement>()
        ?.forEach { appendRichTextElement(it, slackClient, slackDomain) }
        ?: run {
            append(message.text)
        }
}

private suspend fun StringBuilder.appendRichTextElement(element: RichTextElement, slackClient: SlackUserClientImpl, slackDomain: String) {
    when (element) {
        is RichTextSectionElement -> {
            element.elements.forEach { appendRichTextElement(it, slackClient, slackDomain) }
        }
        is RichTextListElement -> {
            element.elements.forEach { listItem ->
                repeat(element.indent) {
                    append("   ")
                }
                append(if (element.style == "ordered") "1. " else "* ")
                appendRichTextElement(listItem, slackClient, slackDomain)
                appendLine()
            }
            appendLine()
        }
        is RichTextPreformattedElement -> {
            appendLine("```")
            element.elements.forEach { appendRichTextElement(it, slackClient, slackDomain) }
            appendLine()
            appendLine("```")
        }
        is RichTextQuoteElement -> {
            element.elements.forEach {
                append("> ")
                appendRichTextElement(it, slackClient, slackDomain)
                appendLine()
            }
        }
        is RichTextSectionElement.Text -> {
            appendStyled(element.style, element.text)
        }
        is RichTextSectionElement.Channel -> {
            slackClient.fetchChannelName(element.channelId)
                ?.channelLink(slackClient, slackDomain, element.channelId)
                ?.let {
                    appendStyled(element.style, it)
                }
        }
        is RichTextSectionElement.User -> {
            slackClient.fetchUserName(element.userId)?.userName()?.let {
                appendStyled(element.style, "`@$it`")
            }
        }
        is RichTextSectionElement.Link -> {
            if (element.text.isNullOrBlank())
                appendStyled(element.style, element.url)
            else
                appendStyled(element.style, "[${element.text}](${element.url})")
        }
        is RichTextSectionElement.Team -> {
            slackClient.fetchTeamName(element.teamId)?.let {
                appendStyled(element.style, "`@${it.team.name}`")
            }
        }
        is RichTextSectionElement.UserGroup -> {
            slackClient.fetchUserGroups()?.usergroups
                ?.firstOrNull { it.id == element.usergroupId }
                ?.let {
                    append(it.name)
                }
        }
        is RichTextSectionElement.Date -> {
            element.timestamp?.toLongOrNull()?.let {
                Instant.fromEpochSeconds(it)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .toJavaLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
        }
        is RichTextSectionElement.Broadcast -> {
            append("`@${element.range}`")
        }
    }
}

private fun StringBuilder.appendStyled(style: TextStyle?, text: String) {
    putStyleMarker(style, true)
    append(text)
    putStyleMarker(style, false)
}

private fun StringBuilder.putStyleMarker(style: TextStyle?, pre: Boolean) {
    if (style != null) {
        val markers = listOfNotNull(
            "**".takeIf { style.isBold },
            "_".takeIf { style.isItalic },
            "~~".takeIf { style.isStrike },
            "`".takeIf { style.isCode }
        )
        if (markers.isNotEmpty()) {
            if (pre)
                append(markers.joinToString(""))
            else
                append(markers.reversed().joinToString(""))
        }
    }
}

private suspend fun disableUnfurling(spaceClientId: String, spaceUserId: String, slackTeamId: String) {
    val spaceOrg = db.spaceOrgs.getById(spaceClientId)
        ?: error("Space org for client id $spaceClientId not found. Reinstall the Space application.")

    db.slackUserTokens.disableUnfurls(
        spaceOrgId = spaceOrg.clientId,
        spaceUserId = spaceUserId,
        slackTeamId = slackTeamId
    )

    log.info("Disabled Slack links unfurling for Space org $spaceClientId, user $spaceUserId, Slack team $slackTeamId")
}

suspend fun requestAuth(
    context: SpaceUserKey,
    queueItems: List<ApplicationUnfurlQueueItem>,
    spaceClient: SpaceClient,
    locations: Locations
) {
    queueItems.forEach { item ->
        spaceClient.applications.unfurls.queue.requestExternalSystemAuthentication(
            item.id,
            unfurl {
                section {
                    text("Authenticate in Slack to get link previews in Space")
                    controls {
                        val slackOAuthUrl = "$entrypointUrl/${
                            locations.href(
                                Routes.SlackOAuth(
                                    spaceOrgId = context.spaceOrgId,
                                    spaceUser = context.spaceUserId,
                                    slackTeamId = context.slackTeamId
                                )
                            )
                        }"
                        button(
                            "Authenticate",
                            NavigateUrlAction(slackOAuthUrl, withBackUrl = true, openInNewTab = false)
                        )
                        button(
                            "Not now",
                            PostMessageAction(AuthAction.NotNow.id, context.slackTeamId),
                            MessageButtonStyle.SECONDARY
                        )
                        button(
                            "Never ask me again",
                            PostMessageAction(AuthAction.Never.id, context.slackTeamId),
                            MessageButtonStyle.SECONDARY
                        )
                    }
                }
            }
        )
    }
    log.info("Requested authentication in Slack for ${queueItems.size} links")
}


private suspend fun ProfileIdentifier.getUserId(client: SpaceClient) =
    when (this) {
        is ProfileIdentifier.Id -> id
        is ProfileIdentifier.Username -> client.teamDirectory.profiles.getProfile(this).id
        else -> null
    }

private fun tsToMessageId(ts: String) =
    "p" + ts.filterNot { it == '.' }

private fun URI.parseQueryParams() =
    query?.split('&').orEmpty().map { param ->
        param.split('=').let {
            if (it.size == 1) it[0] to null
            else it[0] to param.substring(it[0].length + 1)
        }
    }

private const val UNFURL_QUEUE_ITEMS_BATCH_SIZE = 100

private val processUnfurlsChannel = Channel<String>(Channel.BUFFERED)

private val log: Logger = LoggerFactory.getLogger("SlackUnfurlsInSpace")

/** Gets cached Space client with app token authentication */
private fun getSpaceClient(spaceOrg: SpaceOrg): SpaceClient {
    val spaceAppInstance = SpaceAppInstance(
        clientId = spaceOrg.clientId,
        clientSecret = decrypt(spaceOrg.clientSecret),
        spaceServerUrl = spaceOrg.url
    )
    return SpaceClient(spaceHttpClient, spaceAppInstance, SpaceAuth.ClientCredentials())
}

private val spaceHttpClient = ktorClientForSpace()
