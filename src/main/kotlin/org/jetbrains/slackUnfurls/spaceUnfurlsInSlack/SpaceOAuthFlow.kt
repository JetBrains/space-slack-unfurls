package org.jetbrains.slackUnfurls.spaceUnfurlsInSlack

import io.ktor.server.application.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.serialization.json.Json
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
    withSlackLogContext(params.slackTeamId, params.slackUserId, params.spaceOrgId) {
        val spaceOrg = db.spaceOrgs.getById(params.spaceOrgId, params.slackTeamId)
            ?: run {
                call.respondError(HttpStatusCode.BadRequest, log, "Space organization is not connected to Slack workspace")
                return@withSlackLogContext
            }


        val flowId = generateNonce()
        db.spaceUserTokens.delete(params.slackTeamId, params.slackUserId, params.spaceOrgId)
        val permissionScopes = spaceUserPermissionScopes.joinToString(" ")
        db.spaceOAuthSessions.create(flowId, params, permissionScopes)

        log.info("Started user OAuth flow in Space. Flow id is $flowId")

        val authUrl = Space.authCodeSpaceUrl(
            spaceOrg.toSpaceAppInstance(),
            scope = permissionScopes,
            state = flowId,
            redirectUri = callbackUrl,
            requestCredentials = OAuthRequestCredentials.DEFAULT,
            accessType = OAuthAccessType.OFFLINE
        )
        call.respondRedirect(authUrl)
    }
}

suspend fun onUserAuthFlowCompletedInSpace(call: ApplicationCall, params: Routes.SpaceOAuthCallback) {
    if (params.error != null || params.error_description != null) {
        call.respondError(HttpStatusCode.BadRequest, log, "Error while authenticating in Space - ${params.error}, ${params.error_description}")
        return
    }

    if (params.state.isNullOrBlank()) {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected state query string parameter in request")
        return
    }

    if (params.code.isNullOrBlank()) {
        call.respondError(HttpStatusCode.BadRequest, log, "Expected code query string parameter in request")
        return
    }

    val session = db.spaceOAuthSessions.getOnce(params.state)
    if (session == null) {
        call.respondError(
            HttpStatusCode.BadRequest,
            log,
            "Unexpected value of the state query string parameter (flow id = ${params.state})"
        )
        return
    }

    withSlackLogContext(session.slackTeamId, session.slackUserId, session.spaceOrgId) {
        val spaceOrg = db.spaceOrgs.getById(session.spaceOrgId, session.slackTeamId)
        if (spaceOrg == null) {
            call.respondError(HttpStatusCode.BadRequest, log, "Space organization is not connected to Slack workspace")
            return@withSlackLogContext
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
            call.respondError(HttpStatusCode.Unauthorized, log, "Could not get OAuth refresh token from Space")
            return@withSlackLogContext
        }

        db.spaceUserTokens.save(
            slackTeamId = session.slackTeamId,
            slackUserId = session.slackUserId,
            spaceOrgId = session.spaceOrgId,
            accessToken = encrypt(accessToken),
            refreshToken = encrypt(refreshToken),
            permissionScopes = session.permissionScopes
        )

        processDeferredLinkSharedEvents(
            slackTeamId = session.slackTeamId,
            slackUserId = session.slackUserId,
            spaceOrgId = session.spaceOrgId
        )

        val backUrl = session.backUrl
        if (backUrl != null) {
            log.info("Successfully authenticated user in Space, redirecting to back url")
            call.respondRedirect(backUrl)
        } else {
            call.respondSuccess(
                log,
                "Successfully authenticated with Space. Now Space links in your chat messages in Slack will be accompanied with previews."
            )
        }
    }
}

private val spaceOAuthHttpClient by lazy {
    HttpClient {
        install(HttpTimeout)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }
}

/** Permissions for Space refresh token used to fetch content from Space on behalf of the user */
val spaceUserPermissionScopes =
    listOf("global:Project.View", "global:Profile.View") + spaceUnfurlProviders.flatMap { it.spacePermissionScopes }

private val log: Logger = LoggerFactory.getLogger("SpaceOAuthFlow")
