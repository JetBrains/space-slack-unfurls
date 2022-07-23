package org.jetbrains.slackUnfurls

import com.nimbusds.jwt.JWTParser
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.locations.*
import io.ktor.server.locations.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.jetbrains.slackUnfurls.routing.HomepageDataResponse
import org.jetbrains.slackUnfurls.routing.Routes
import org.jetbrains.slackUnfurls.routing.SlackTeamOut
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.ProvideUnfurlsRightCode
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.SlackAppClient
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.SlackDomain
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.getSpaceClient
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.slackAppPermissionScopes
import org.jetbrains.slackUnfurls.storage.SlackTeam
import org.jetbrains.slackUnfurls.storage.SpaceOrg
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import space.jetbrains.api.runtime.resources.applications
import space.jetbrains.api.runtime.resources.permissions
import space.jetbrains.api.runtime.types.*

private val log: Logger = LoggerFactory.getLogger("SpaceAppHomepage")

fun Routing.routesForSpaceHomepage() {
    // TODO: make index.html refer to js file inside `space-iframe` to simplify this routing
    static("/space-iframe") {
        staticBasePackage = "space-iframe"
        resources(".")
        defaultResource("index.html")
    }
    static("/") {
        staticBasePackage = "space-iframe"
        resources(".")
    }

    get<Routes.GetHomepageData> {
        runAuthorizedWithContext { context ->
            val teams = db.slackTeams.getForSpaceOrg(context.spaceOrg.clientId).mapNotNull {
                val (iconUrl, name) = iconUrlAndName(it) ?: return@mapNotNull null
                SlackTeamOut(it.id, it.domain, iconUrl, name)
            }

            call.respond(
                HttpStatusCode.OK,
                HomepageDataResponse(
                    teams = teams,
                    canManage = context.canManage,
                    unapprovedPermissions = context.unapprovedPermissions,
                    unapprovedUnfurlDomains = context.unapprovedUnfurlDomains,
                    hasPermissionToApprove = context.hasPermissionToApprove,
                )
            )
        }
    }

    get<Routes.UrlForAddingSlackTeam> {
        runAuthorizedWithContext { context ->
            if (!context.canManage) {
                call.respond(HttpStatusCode.Unauthorized)
                return@runAuthorizedWithContext
            }

            withContext(MDCContext(mapOf(MDCParams.SPACE_ORG to context.spaceOrg.clientId))) {
                log.info("Adding Slack team to Space org")
            }

            val installToSlackUrl = URLBuilder("https://slack.com/oauth/v2/authorize").run {
                parameters.apply {
                    append("client_id", SlackCredentials.clientId)
                    append("scope", slackAppPermissionScopes.joinToString(","))
                    append("user_scope", "")
                    append("state", "org-${context.spaceOrg.clientId}")
                }
                buildString()
            }
            call.respond(HttpStatusCode.OK, installToSlackUrl)
        }
    }

    post<Routes.RemoveSlackTeam> { params ->
        runAuthorizedWithContext { context ->
            if (!context.canManage) {
                call.respond(HttpStatusCode.Unauthorized)
                return@runAuthorizedWithContext
            }

            db.slackTeams.disconnectFromSpaceOrg(params.slackTeamId, context.spaceOrg.clientId)
            withContext(
                MDCContext(
                    mapOf(
                        MDCParams.SLACK_TEAM to params.slackTeamId,
                        MDCParams.SPACE_ORG to context.spaceOrg.clientId
                    )
                )
            ) {
                log.info("Disconnected Slack team from Space org")
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}

private suspend fun iconUrlAndName(teamFromDb: SlackTeam): Pair<String, String>? {
    if (teamFromDb.iconUrl != null && teamFromDb.name != null) {
        return teamFromDb.iconUrl to teamFromDb.name
    }

    // get iconUrl and team name from Slack and upgrade old data rows
    val slackClient = SlackAppClient.tryCreate(teamFromDb.id, log) ?: return null
    val team = slackClient.getTeamInfo() ?: return null
    val (iconUrl, name) = team.icon.image44 to team.name
    db.slackTeams.updateIconUrlAndName(teamFromDb.id, iconUrl, name)

    return iconUrl to name
}

private suspend fun canManageApplication(spaceOrg: SpaceOrg, spaceUserToken: String): Boolean {
    return getSpaceClient(spaceOrg, spaceUserToken).permissions.checkPermission(
        PrincipalIn.Profile(ProfileIdentifier.Me),
        "Superadmin",
        GlobalPermissionTarget
    )
}

private suspend fun unapprovedPermissions(spaceOrg: SpaceOrg): List<String> {
    val requiredPermissions = listOf(ProvideUnfurlsRightCode)
    val spaceClient = getSpaceClient(spaceOrg)

    return requiredPermissions
        .filterNot { uniqueRightCode ->
            spaceClient.permissions.checkPermission(
                principal = PrincipalIn.Application(ApplicationIdentifier.Me),
                uniqueRightCode = uniqueRightCode,
                target = GlobalPermissionTarget
            )
        }
        .map { "global:$it" }
}

@Suppress("ConvertArgumentToSet")
private suspend fun unapprovedUnfurlDomains(spaceOrg: SpaceOrg): List<String> {
    val requiredDomains = listOf(SlackDomain)
    val approvedDomains = getSpaceClient(spaceOrg).applications.unfurlDomains
        .getAllUnfurlDomains(ApplicationIdentifier.Me)
        .filter { it.status == RightStatus.GRANTED }
        .map { it.domain }

    return requiredDomains - approvedDomains
}

private data class HomepageContext(
    val spaceOrg: SpaceOrg,
    val canManage: Boolean,
    val unapprovedPermissions: String?,
    val unapprovedUnfurlDomains: String?,
    val hasPermissionToApprove: Boolean,
)

private suspend fun PipelineContext<Unit, ApplicationCall>.runAuthorizedWithContext(block: suspend (HomepageContext) -> Unit) {
    val context = (context.request.parseAuthorizationHeader() as? HttpAuthHeader.Single)?.blob
        ?.let { getHomepageContext(it) }
        ?: run {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

    block(context)
}

private suspend fun getHomepageContext(spaceUserToken: String): HomepageContext? {
    val jwtClaimsSet = JWTParser.parse(spaceUserToken).jwtClaimsSet
    val spaceOrg = jwtClaimsSet.audience.singleOrNull()?.let { db.spaceOrgs.getById(it) } ?: return null
    val canManage = canManageApplication(spaceOrg, spaceUserToken)
    return HomepageContext(
        spaceOrg = spaceOrg,
        canManage = canManage,
        unapprovedPermissions = unapprovedPermissions(spaceOrg).takeIf { it.isNotEmpty() }?.joinToString(" "),
        unapprovedUnfurlDomains = unapprovedUnfurlDomains(spaceOrg).takeIf { it.isNotEmpty() }?.joinToString(" "),
        hasPermissionToApprove = canManage,
    )
}
