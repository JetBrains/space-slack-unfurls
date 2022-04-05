package org.jetbrains.slackUnfurls.slackUnfurlsInSpace

import com.slack.api.methods.response.oauth.OAuthV2AccessResponse
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.util.*
import kotlinx.coroutines.channels.Channel
import kotlinx.css.code
import org.jetbrains.slackUnfurls.SlackCredentials
import org.jetbrains.slackUnfurls.db
import org.jetbrains.slackUnfurls.encrypt
import org.jetbrains.slackUnfurls.html.respondError
import org.jetbrains.slackUnfurls.html.respondSuccess
import org.jetbrains.slackUnfurls.routing.Routes
import org.jetbrains.slackUnfurls.storage.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory


suspend fun startUserAuthFlowInSlack(call: ApplicationCall, params: Routes.SlackOAuth, callbackUrl: String) {
    val flowId = generateNonce()
    val slackDomain = db.slackTeams.getById(params.slackTeamId)?.domain
    if (slackDomain == null) {
        log.warn("Slack team ${params.slackTeamId} not found")
        return
    }

    db.slackUserTokens.delete(
        spaceOrgId = params.spaceOrgId,
        spaceUserId = params.spaceUser,
        slackTeamId = params.slackTeamId
    )
    db.slackOAuthSessions.create(flowId, params)
    log.info(
        "Started OAuth flow in Slack for Space org ${params.spaceOrgId}, user ${params.spaceUser}, slack team ${params.slackTeamId}. Flow id is $flowId"
    )

    val authUrl = with(URLBuilder("https://$slackDomain.slack.com/oauth/v2/authorize")) {
        parameters.apply {
            append("client_id", SlackCredentials.clientId)
            append("user_scope", slackPermissionScopes.joinToString(","))
            append("state", flowId)
            append("redirect_uri", callbackUrl)
        }
        build()
    }
    call.respondRedirect(authUrl.toString())
}

suspend fun onUserAuthFlowCompletedInSlack(call: ApplicationCall, flowId: String, params: Routes.SlackOAuthCallback) {
    val shortLogPrefix = "Flow id = $flowId"

    if (params.code.isNullOrBlank()) {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected code query string parameter in request", shortLogPrefix)
        return
    }

    val session = db.slackOAuthSessions.getOnce(flowId) ?: run {
        call.respondError(
            HttpStatusCode.BadRequest,
            log,
            "Unexpected value of the state query string parameter",
            shortLogPrefix
        )
        return
    }

    val fullLogPrefix = "Flow id = $flowId, Space org ${session.spaceOrgId}, user ${session.spaceUserId}, slack team ${session.slackTeamId}"

    val response = requestOAuthToken(params.code)
    if (response == null || response.authedUser?.accessToken.isNullOrBlank()) {
        call.respondError(HttpStatusCode.Unauthorized, log, "Could not fetch OAuth token from Slack", fullLogPrefix)
        return
    }

    db.slackUserTokens.save(
        spaceOrgId = session.spaceOrgId,
        spaceUserId = session.spaceUserId,
        slackTeamId = session.slackTeamId,
        accessToken = encrypt(response.authedUser.accessToken),
        refreshToken = encrypt(response.authedUser.refreshToken)
    )
    processUnfurlsAfterAuthChannel.send(session)

    if (session.backUrl != null) {
        log.info("$fullLogPrefix. Successfully authenticated user in Slack, redirecting to back url")
        call.respondRedirect(session.backUrl)
    } else {
        // back url should be always present in this flow, but we cannot statically verify this
        call.respondSuccess(
            log,
            "Successfully authenticated with Slack. Now Slack links in your chat messages in JetBrains Space will be accompanied with previews.",
            fullLogPrefix
        )
    }
}

suspend fun onAppInstalledToSlackTeam(call: ApplicationCall, params: Routes.SlackOAuthCallback) {
    if (params.code.isNullOrBlank()) {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected code query string parameter in request", "")
        return
    }

    val response = requestOAuthToken(params.code)
    val accessToken = response?.accessToken
    val refreshToken = response?.refreshToken
    val teamId = response?.team?.id
    if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || teamId.isNullOrBlank()) {
        val message = "Could not fetch OAuth token from Slack. " +
                "Team id = $teamId, " +
                "access token is ${if (accessToken.isNullOrBlank()) "empty" else "provided" }, " +
                "refresh token is ${if (refreshToken.isNullOrBlank()) "empty" else "provided"}"
        call.respondError(HttpStatusCode.Unauthorized, log, message, "Code = $code")
        return
    }

    val teamResponse = slackApiClient.methods(accessToken).teamInfo { it.team(teamId) }
    if (teamResponse.team == null) {
        call.respondError(
            HttpStatusCode.Unauthorized,
            log,
            "Could not fetch team info from Slack - ${teamResponse.error}",
            "Team id = ${response.team.id}"
        )
        return
    }

    db.slackTeams.create(teamId, teamResponse.team.domain, encrypt(accessToken), encrypt(refreshToken))

    call.respondSuccess(
        log,
        "Application successfully installed to Slack team",
        "Slack team $teamId"
    )
}


fun requestOAuthToken(code: String): OAuthV2AccessResponse? {
    val response = slackApiClient.methods().oauthV2Access {
        it.clientId(SlackCredentials.clientId).clientSecret(SlackCredentials.clientSecret).code(code)
    }

    if (!response.isOk) {
        log.warn("Got ok=false while trying to refresh access token - ${response.error}")
        return null
    }

    return response
}


private val slackPermissionScopes = listOf(
    "channels:history",
    "groups:history",
    "im:history",
    "channels:read",
    "groups:read",
    "im:read",
    "team:read",
    "users:read",
    "usergroups:read",
)

private val log: Logger = LoggerFactory.getLogger("SlackOAuthFlow")

data class SpaceUserKey(val spaceOrgId: String, val spaceUserId: String, val slackTeamId: String) {
    override fun toString() =
        "Space org $spaceOrgId, user $spaceUserId, Slack team $slackTeamId"
}

val processUnfurlsAfterAuthChannel = Channel<SlackOAuthSession>(Channel.BUFFERED)