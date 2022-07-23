package org.jetbrains.slackUnfurls.routing

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.locations.*
import io.ktor.server.locations.post
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.slackUnfurls.entrypointUrl
import org.jetbrains.slackUnfurls.routesForSpaceHomepage
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.onAppInstalledToSlackTeam
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.onSpaceCall
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.onUserAuthFlowCompletedInSlack
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.startUserAuthFlowInSlack
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.onSlackEvent
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.onSlackInteractive
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.onUserAuthFlowCompletedInSpace
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.startUserAuthFlowInSpace


fun Application.configureRouting() {
    install(Locations)
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK)
        }

        routesForSpaceHomepage()

        get<Routes.SlackOAuth> { params ->
            val callbackUrl = locations.href(Routes.SlackOAuthCallback())
            startUserAuthFlowInSlack(call, params, "$entrypointUrl$callbackUrl")
        }

        get<Routes.SlackOAuthCallback> { params ->
            val flowId = params.state
            when {
                flowId.isNullOrBlank() ->
                    call.respond(HttpStatusCode.BadRequest, "Expected state param in callback request")
                flowId.startsWith("user-") ->
                    onUserAuthFlowCompletedInSlack(call, flowId.removePrefix("user-"), params)
                flowId.startsWith("org-") ->
                    onAppInstalledToSlackTeam(call, flowId.removePrefix("org-"), params)
                else ->
                    call.respond(HttpStatusCode.BadRequest, "Malformed state param in callback request")
            }
        }

        post<Routes.SpaceApiEndpoint> {
            onSpaceCall(call)
        }

        get<Routes.SpaceOAuth> { params ->
            val callbackUrl = locations.href(Routes.SpaceOAuthCallback())
            startUserAuthFlowInSpace(call, params, "$entrypointUrl$callbackUrl")
        }

        get<Routes.SpaceOAuthCallback> { params ->
            onUserAuthFlowCompletedInSpace(call, params)
        }

        post<Routes.SlackEvents> {
            onSlackEvent(call)
        }

        post<Routes.SlackInteractive> {
            onSlackInteractive(call)
        }
    }
}
