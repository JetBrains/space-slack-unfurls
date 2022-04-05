package org.jetbrains.slackUnfurls.spaceUnfurlsInSlack

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.util.*
import org.jetbrains.slackUnfurls.*
import org.jetbrains.slackUnfurls.html.respondError
import org.jetbrains.slackUnfurls.html.respondSuccess
import org.jetbrains.slackUnfurls.routing.Routes
import org.jetbrains.slackUnfurls.storage.toSpaceAppInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import space.jetbrains.api.runtime.OAuthAccessType
import space.jetbrains.api.runtime.OAuthRequestCredentials
import space.jetbrains.api.runtime.Space


suspend fun startUserAuthFlowInSpace(call: ApplicationCall, params: Routes.SpaceOAuth, callbackUrl: String) {
    val spaceOrg = db.spaceOrgs.getById(params.spaceOrgId)
        ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                log,
                "Application not installed to Space organization ${params.spaceOrgId}"
            )
            return
        }


    val flowId = generateNonce()
    db.spaceUserTokens.delete(params.slackTeamId, params.slackUserId, params.spaceOrgId)
    db.spaceOAuthSessions.create(flowId, params)

    log.info("Started OAuth flow in Space for slack team ${params.slackTeamId}, user ${params.slackUserId}, space org ${params.spaceOrgId}. Flow id is $flowId")

    val authUrl = Space.authCodeSpaceUrl(
        spaceOrg.toSpaceAppInstance(),
        scope = spaceUserPermissionScopes.joinToString(" "),
        state = flowId,
        redirectUri = callbackUrl,
        requestCredentials = OAuthRequestCredentials.DEFAULT,
        accessType = OAuthAccessType.OFFLINE
    )
    call.respondRedirect(authUrl)
}

suspend fun onUserAuthFlowCompletedInSpace(call: ApplicationCall, params: Routes.SpaceOAuthCallback) {
    if (params.error != null || params.error_description != null) {
        call.respondError(HttpStatusCode.BadRequest, log, "Error while authenticating in Space - ${params.error}, ${params.error_description}")
        return
    }

    if (params.state.isNullOrBlank()) {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected state query string parameter in request", "")
        return
    }

    if (params.code.isNullOrBlank()) {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected code query string parameter in request", "")
        return
    }

    val session = db.spaceOAuthSessions.getOnce(params.state)
    if (session == null) {
        call.respondError(
            HttpStatusCode.BadRequest,
            log,
            "Unexpected value of the state query string parameter",
            "Flow id = ${params.state}"
        )
        return
    }

    val logPrefix = "Slack team ${session.slackTeamId}, user ${session.slackUserId}, Space org ${session.spaceOrgId}"

    val spaceOrg = db.spaceOrgs.getById(session.spaceOrgId)
    if (spaceOrg == null) {
        call.respondError(HttpStatusCode.BadRequest, log, "Application not installed to Space organization", logPrefix)
        return
    }

    val (accessToken, refreshToken) = Space
        .exchangeAuthCodeForToken(
            spaceOAuthHttpClient,
            spaceOrg.toSpaceAppInstance(),
            params.code,
            redirectUri = "$entrypointUrl/space/oauth/callback"
        )
        .let { it.accessToken to it.refreshToken }
    if (accessToken.isBlank() || refreshToken.isNullOrBlank()) {
        call.respondError(HttpStatusCode.Unauthorized, log, "Could not get OAuth refresh token from Space", logPrefix)
        return
    }

    db.spaceUserTokens.save(
        slackTeamId = session.slackTeamId,
        slackUserId = session.slackUserId,
        spaceOrgId = session.spaceOrgId,
        accessToken = encrypt(accessToken),
        refreshToken = encrypt(refreshToken)
    )

    processDeferredLinkSharedEvents(
        slackTeamId = session.slackTeamId,
        slackUserId = session.slackUserId,
        spaceOrgId = session.spaceOrgId
    )

    val backUrl = session.backUrl
    if (backUrl != null) {
        log.info("$logPrefix. Successfully authenticated user in Space, redirecting to back url")
        call.respondRedirect(backUrl)
    } else {
        call.respondSuccess(
            log,
            "Successfully authenticated with Space. Now Space links in your chat messages in Slack will be accompanied with previews.",
            logPrefix
        )
    }
}

private val spaceOAuthHttpClient by lazy {
    HttpClient {
        install(HttpTimeout)
        Json {
            serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            })
        }
    }
}

/** Permissions for Space refresh token used to fetch content from Space on behalf of the user */
val spaceUserPermissionScopes =
    listOf("global:Project.View", "global:Profile.View") + spaceUnfurlProviders.flatMap { it.spacePermissionScopes }

private val log: Logger = LoggerFactory.getLogger("SpaceOAuthFlow")
