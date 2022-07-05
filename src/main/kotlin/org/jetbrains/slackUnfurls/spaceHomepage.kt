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
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.ProvideUnfurlsRightCode
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.SlackDomain
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.getSpaceClient
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.slackAppPermissionScopes
import org.jetbrains.slackUnfurls.storage.SpaceOrg
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.permissions
import space.jetbrains.api.runtime.types.*

private val log: Logger = LoggerFactory.getLogger("SpaceAppHomepage")

fun Routing.routesForSpaceHomepage() {

    get<Routes.SpaceAppHomepage> { params ->
        call.respondHtml {
            homepageHtml(params.backgroundColor)
        }
    }

    get<Routes.GetSlackTeams> {
        val context = getHomepageContext() ?: run {
            call.respond(HttpStatusCode.Unauthorized)
            return@get
        }

        val teams = db.slackTeams.getForSpaceOrg(context.spaceOrg.clientId).map {
            SlackTeamOut(it.id, it.domain)
        }
        call.respond(
            HttpStatusCode.OK,
            SlackTeamsResponse(
                teams = teams,
                canManage = context.canManage,
                permissionsApproved = context.permissionsApproved,
                unfurlDomainApproved = context.unfurlDomainApproved
            )
        )
    }

    post<Routes.AddSlackTeam> {
        val spaceOrg = getHomepageContext()?.takeIf { it.canManage }?.spaceOrg
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
        val spaceOrg = getHomepageContext()?.takeIf { it.canManage }?.spaceOrg
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

private suspend fun arePermissionsApproved(spaceOrg: SpaceOrg): Boolean {
    return getSpaceClient(spaceOrg).applications.authorizations.authorizedRights
        .getAllAuthorizedRights(ApplicationIdentifier.Me, GlobalPermissionContextIdentifier)
        .any { it.status == RightStatus.GRANTED && it.rightCode == ProvideUnfurlsRightCode }
}

private suspend fun areUnfurlDomainsApproved(spaceOrg: SpaceOrg): Boolean {
    return getSpaceClient(spaceOrg).applications.unfurlDomains
        .getAllUnfurlDomains(ApplicationIdentifier.Me)
        .any {
            it.status == RightStatus.GRANTED && it.domain == SlackDomain
        }
}

private data class HomepageContext(
    val spaceOrg: SpaceOrg,
    val canManage: Boolean,
    val permissionsApproved: Boolean,
    val unfurlDomainApproved: Boolean
)

private suspend fun PipelineContext<Unit, ApplicationCall>.getHomepageContext(): HomepageContext? =
    (context.request.parseAuthorizationHeader() as? HttpAuthHeader.Single)?.blob
        ?.let { getHomepageContext(it) }

private suspend fun getHomepageContext(spaceUserToken: String): HomepageContext? {
    val jwtClaimsSet = JWTParser.parse(spaceUserToken).jwtClaimsSet
    val spaceOrg = jwtClaimsSet.audience.singleOrNull()?.let { db.spaceOrgs.getById(it) } ?: return null
    val spaceUserId = jwtClaimsSet.subject
    return HomepageContext(
        spaceOrg = spaceOrg,
        canManage = canManageApplication(spaceOrg, spaceUserId, spaceUserToken),
        permissionsApproved = arePermissionsApproved(spaceOrg),
        unfurlDomainApproved = areUnfurlDomainsApproved(spaceOrg)
    )
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
            id = "grant-permissions-block"
            classes = setOf("hidden")

            +"Go to Authorization and Unfurls tabs and make sure all permission and unfurl domain requests are approved there"
        }

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
