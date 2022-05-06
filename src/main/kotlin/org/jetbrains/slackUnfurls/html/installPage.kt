package org.jetbrains.slackUnfurls.html

import kotlinx.html.*
import org.jetbrains.slackUnfurls.entrypointUrl
import space.jetbrains.api.runtime.AuthForMessagesFromSpace
import space.jetbrains.api.runtime.Space
import space.jetbrains.api.runtime.SpaceAuthFlow
import space.jetbrains.api.runtime.appInstallUrl

fun HTML.installPage() = page(
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
        script(type = "text/javascript", src = "/static/installPage.js") {
            defer = true
        }
    }
) {
    p {
        +"Your Space org domain:"
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
}
