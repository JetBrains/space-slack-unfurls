package org.jetbrains.slackUnfurls.routing

import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.http.*
import io.ktor.server.http.content.*
import io.ktor.server.locations.*
import io.ktor.server.locations.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.slackUnfurls.SlackCredentials
import org.jetbrains.slackUnfurls.entrypointUrl
import org.jetbrains.slackUnfurls.html.installPage
import org.jetbrains.slackUnfurls.slackUnfurlsInSpace.*
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.*


fun Application.configureRouting() {
    install(Locations)

    routing {
        get("/") {
            call.respondHtml(HttpStatusCode.OK) {
                installPage(SlackCredentials.clientId)
            }
        }

        get("/health") {
            call.respond(HttpStatusCode.OK)
        }

        static("static") {
            resources("static")
        }

        get<Routes.SlackOAuth> { params ->
            val callbackUrl = locations.href(Routes.SlackOAuthCallback())
            startUserAuthFlowInSlack(call, params, "$entrypointUrl$callbackUrl")
        }

        get<Routes.SlackOAuthCallback> { params ->
            val flowId = params.state
            if (!flowId.isNullOrBlank()) {
                onUserAuthFlowCompletedInSlack(call, flowId, params)
            } else {
                onAppInstalledToSlackTeam(call, params)
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
