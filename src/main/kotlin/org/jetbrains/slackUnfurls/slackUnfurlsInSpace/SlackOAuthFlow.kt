package org.jetbrains.slackUnfurls.slackUnfurlsInSpace

import com.slack.api.methods.response.oauth.OAuthV2AccessResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.jetbrains.slackUnfurls.*
import org.jetbrains.slackUnfurls.html.respondError
import org.jetbrains.slackUnfurls.html.respondSuccess
import org.jetbrains.slackUnfurls.routing.Routes
import org.jetbrains.slackUnfurls.storage.SlackOAuthSession
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.types.ApplicationIdentifier


suspend fun startUserAuthFlowInSlack(call: ApplicationCall, params: Routes.SlackOAuth, callbackUrl: String) {
    withSpaceLogContext(params.spaceOrgId, params.spaceUser, params.slackTeamId) {
        val flowId = generateNonce()
        val slackDomain = db.slackTeams.getById(params.slackTeamId, params.spaceOrgId)?.domain
        if (slackDomain == null) {
            log.warn("Slack workspace is not connected to Space org")
            return@withSpaceLogContext
        }

        db.slackUserTokens.delete(
            spaceOrgId = params.spaceOrgId,
            spaceUserId = params.spaceUser,
            slackTeamId = params.slackTeamId
        )

        val permissionScopes = slackPermissionScopes.joinToString(",")
        db.slackOAuthSessions.create(flowId, params, permissionScopes)
        log.info("Started user OAuth flow in Slack. Flow id is $flowId")

        val authUrl = with(URLBuilder("https://$slackDomain.slack.com/oauth/v2/authorize")) {
            parameters.apply {
                append("client_id", SlackCredentials.clientId)
                append("user_scope", permissionScopes)
                append("state", "user-$flowId")
                append("redirect_uri", callbackUrl)
            }
            build()
        }
        call.respondRedirect(authUrl.toString())
    }
}

suspend fun onUserAuthFlowCompletedInSlack(call: ApplicationCall, flowId: String, params: Routes.SlackOAuthCallback) {
    if (params.code.isNullOrBlank()) {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected code query string parameter in request (flow id = $flowId)")
        return
    }

    val session = db.slackOAuthSessions.get(flowId) ?: run {
        call.respondError(
            HttpStatusCode.BadRequest,
            log,
            "Authentication session has expired, try again (flow id = $flowId)"
        )
        return
    }

    withSpaceLogContext(session.spaceOrgId, session.spaceUserId, session.slackTeamId, "flow-id" to flowId) {
        val response = requestOAuthToken(params.code)
        if (response == null || response.authedUser?.accessToken.isNullOrBlank()) {
            call.respondError(
                HttpStatusCode.Unauthorized,
                log,
                "Could not fetch OAuth token from Slack (flow id = $flowId)"
            )
            return@withSpaceLogContext
        }

        db.slackUserTokens.save(
            spaceOrgId = session.spaceOrgId,
            spaceUserId = session.spaceUserId,
            slackTeamId = session.slackTeamId,
            accessToken = encrypt(response.authedUser.accessToken),
            refreshToken = encrypt(response.authedUser.refreshToken),
            permissionScopes = session.permissionScopes
        )
        processUnfurlsAfterAuthChannel.send(session)

        if (session.backUrl != null) {
            log.info("Successfully authenticated user in Slack, redirecting to back url")
            call.respondRedirect(session.backUrl)
        } else {
            // back url should be always present in this flow, but we cannot statically verify this
            call.respondSuccess(
                log,
                "Successfully authenticated with Slack. Now Slack links in your chat messages in JetBrains Space will be accompanied with previews."
            )
        }
    }
}

suspend fun onAppInstalledToSlackTeam(call: ApplicationCall, spaceOrgId: String, params: Routes.SlackOAuthCallback) {
    if (params.code.isNullOrBlank()) {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected code query string parameter in request")
        return
    }

    val response = requestOAuthToken(params.code)
    val accessToken = response?.accessToken
    val refreshToken = response?.refreshToken
    val teamId = response?.team?.id
    withContext(MDCContext(mapOf(MDCParams.SLACK_TEAM to teamId.orEmpty(), MDCParams.SPACE_ORG to spaceOrgId))) {
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || teamId.isNullOrBlank()) {
            val message = "Could not fetch OAuth token from Slack. " +
                    "Team id = $teamId, " +
                    "access token is ${if (accessToken.isNullOrBlank()) "empty" else "provided"}, " +
                    "refresh token is ${if (refreshToken.isNullOrBlank()) "empty" else "provided"}, " +
                    "code = ${params.code}"
            call.respondError(HttpStatusCode.Unauthorized, log, message)
            return@withContext
        }

        val teamResponse = slackApiClient.methods(accessToken).teamInfo { it.team(teamId) }
        if (teamResponse.team == null) {
            call.respondError(
                HttpStatusCode.Unauthorized, log, "Could not fetch team info from Slack - ${teamResponse.error}"
            )
            return@withContext
        }

        val spaceOrg = db.spaceOrgs.getById(spaceOrgId) ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                log,
                "Unexpected value of the state query string parameter (flow id = $spaceOrgId)"
            )
            return@withContext
        }
        val spaceApp = getSpaceClient(spaceOrg).applications.getApplication(ApplicationIdentifier.Me)

        db.slackTeams.create(teamId, teamResponse.team.domain, spaceOrgId, encrypt(accessToken), encrypt(refreshToken), teamResponse.team.icon?.image44, teamResponse.team.name)

        log.info("Slack team connected to Space org")
        val backUrl = URLBuilder(spaceOrg.url).run {
            path("extensions", "installedApplications", "${spaceApp.name}-${spaceApp.id}", "homepage")
            buildString()
        }
        call.respondRedirect(backUrl)
    }
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
    "channels:read",
    "groups:read",
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
