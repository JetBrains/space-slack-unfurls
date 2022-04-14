package org.jetbrains.slackUnfurls.html

import kotlinx.html.*
import org.jetbrains.slackUnfurls.entrypointUrl
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.slackAppPermissionScopes
import space.jetbrains.api.runtime.AuthForMessagesFromSpace
import space.jetbrains.api.runtime.Space
import space.jetbrains.api.runtime.SpaceAuthFlow
import space.jetbrains.api.runtime.appInstallUrl

fun HTML.installPage(slackClientId: String) = page(
    initHead = {
        val spaceAppInstallUrl = Space.appInstallUrl(
            spaceServerUrl = "https://space-org-hostname",
            name = "Slack unfurls",
            appEndpoint = "$entrypointUrl/space/api",
            authFlows = setOf(
                SpaceAuthFlow.ClientCredentials,
                SpaceAuthFlow.AuthorizationCode(listOf("$entrypointUrl/space/oauth/callback"), pkceRequired = false)
            ),
            authForMessagesFromSpace = AuthForMessagesFromSpace.PUBLIC_KEY_SIGNATURE
        )

        script(type = "text/javascript") {
            unsafe { +"window.spaceAppInstallUrl = '$spaceAppInstallUrl'" }
        }
        script(type = "text/javascript", src = "/static/install.js") {
            defer = true
        }
    }
) {
    p {
        +"This application can be installed to Slack and JetBrains Space to provide link previews in both directions."
    }

    hr()

    p {
        +"Enter your Space org domain and press `Add to Space` button to install application to Space."
    }

    textInput {
        id = "space-org-input"
    }

    a(href = "#", target = "_blank") {
        id = "install-to-space-link"
        classes = setOf("empty")

        img {
            alt = "Add to Space"
            src = "/static/space.png"
            height = "30"
        }
        span {
            +Entities.nbsp
            +"Add to Space"
        }
    }

    hr()

    p {
        +"For Slack application installation, you will be presented with the workspace selection after pressing the `Add to Slack` button."
    }

    div {
        val scope = slackAppPermissionScopes.joinToString(",")
        val installToSlackUrl = "https://slack.com/oauth/v2/authorize?client_id=$slackClientId&scope=$scope&user_scope="

        a(installToSlackUrl, target = "_blank") {
            img(
                alt = "Add to Slack",
                src = "https://platform.slack-edge.com/img/add_to_slack.png"
            ) {
                height = "40"
                width = "139"
            }
        }
    }
}
