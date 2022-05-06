package org.jetbrains.slackUnfurls

import com.nimbusds.jwt.JWTParser
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.html.*
import io.ktor.server.locations.*
import io.ktor.server.locations.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.html.*
import org.jetbrains.slackUnfurls.routing.Routes
import org.jetbrains.slackUnfurls.routing.SlackTeamOut
import org.jetbrains.slackUnfurls.routing.SlackTeamsResponse
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.getSpaceClient
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.slackAppPermissionScopes
import org.jetbrains.slackUnfurls.storage.SpaceOrg
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.permissions
import space.jetbrains.api.runtime.types.ApplicationIdentifier
import space.jetbrains.api.runtime.types.GlobalPermissionTarget
import space.jetbrains.api.runtime.types.PrincipalIn
import space.jetbrains.api.runtime.types.ProfileIdentifier

private val log: Logger = LoggerFactory.getLogger("SpaceAppHomepage")

fun Routing.routesForSpaceHomepage() {

    get<Routes.SpaceAppHomepage> { params ->
        call.respondHtml {
            homepageHtml(params.backgroundColor)
        }
    }

    get<Routes.GetSlackTeams> {
        val (spaceOrg, canManage) = getSpaceOrgFromAuthToken() ?: run {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }

        val teams = db.slackTeams.getForSpaceOrg(spaceOrg.clientId).map {
            SlackTeamOut(it.id, it.domain)
        }
        call.respond(HttpStatusCode.OK, SlackTeamsResponse(teams, canManage))
    }

    post<Routes.AddSlackTeam> {
        val spaceOrg = getSpaceOrgFromAuthToken()?.takeIf { it.second }?.first
        if (spaceOrg == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        withContext(MDCContext(mapOf(MDCParams.SPACE_ORG to spaceOrg.clientId))) {
            log.info("Adding Slack team to Space org")
        }

        val installToSlackUrl = URLBuilder("https://slack.com/oauth/v2/authorize").run {
            parameters.apply {
                append("client_id", SlackCredentials.clientId)
                append("scope", slackAppPermissionScopes.joinToString(","))
                append("user_scope", "")
                append("state", "org-${spaceOrg.clientId}")
            }
            buildString()
        }
        call.respond(HttpStatusCode.OK, installToSlackUrl)
    }

    post<Routes.RemoveSlackTeam> { params ->
        val spaceOrg = getSpaceOrgFromAuthToken()?.takeIf { it.second }?.first
        if (spaceOrg == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        db.slackTeams.disconnectFromSpaceOrg(params.slackTeamId, spaceOrg.clientId)
        withContext(MDCContext(mapOf(MDCParams.SLACK_TEAM to params.slackTeamId, MDCParams.SPACE_ORG to spaceOrg.clientId))) {
            log.info("Disconnected Slack team from Space org")
        }
        call.respond(HttpStatusCode.OK)
    }
}

private suspend fun canManageApplication(spaceOrg: SpaceOrg, spaceUserId: String, spaceUserToken: String): Boolean {
    val spaceApp = getSpaceClient(spaceOrg).applications.getApplication(ApplicationIdentifier.Me)
    if (spaceApp.owner?.id == spaceUserId)
        return true

    return getSpaceClient(spaceOrg, spaceUserToken).permissions.checkPermission(
        PrincipalIn.Profile(ProfileIdentifier.Me),
        "Applications.Edit",
        GlobalPermissionTarget
    )
}

private suspend fun PipelineContext<Unit, ApplicationCall>.getSpaceOrgFromAuthToken(): Pair<SpaceOrg, Boolean>? =
    (context.request.parseAuthorizationHeader() as? HttpAuthHeader.Single)?.blob
        ?.let { getSpaceOrgFromAuthToken(it) }

private suspend fun getSpaceOrgFromAuthToken(spaceUserToken: String): Pair<SpaceOrg, Boolean>? {
    val jwtClaimsSet = JWTParser.parse(spaceUserToken).jwtClaimsSet
    val spaceOrg = jwtClaimsSet.audience.singleOrNull()?.let { db.spaceOrgs.getById(it) } ?: return null
    val spaceUserId = jwtClaimsSet.subject
    return spaceOrg to canManageApplication(spaceOrg, spaceUserId, spaceUserToken)
}

fun HTML.homepageHtml(backgroundColor: String) {
    head {
        style {
            unsafe {
                +":root { background-color: $backgroundColor }"
            }
        }
        styleLink("/static/slackAppHomepage.css")
        script(type = "text/javascript", src = "/static/slackAppHomepage.js") {
            defer = true
        }
    }

    body {
        div {
            classes = setOf("heading")
            +"Where JetBrains Space meets Slack"
        }

        p { +"This application connects Slack and JetBrains Space to provide link previews in both directions." }

        div {
            id = "slack-teams-block"
            classes = setOf("hidden")

            div {
                id = "slack-teams-list-header"
                +"Slack teams"
            }

            ul {
                id = "slack-teams-list"
            }

            button {
                id = "add-slack-team-btn"

                img {
                    alt = "Add Slack Team"
                    src = "/static/slack.png"
                    height = "20px"
                }
                span {
                    +"Add Slack Team"
                }
            }
        }
    }
}
