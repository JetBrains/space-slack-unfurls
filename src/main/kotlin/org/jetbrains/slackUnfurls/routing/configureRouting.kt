package org.jetbrains.slackUnfurls.routing

import io.ktor.application.*
import io.ktor.html.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.locations.*
import io.ktor.locations.post
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
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

        route("*") {
            handle {
                log.info("Unhandled route - ${call.request.httpMethod.value} ${call.request.uri}")
                call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}
