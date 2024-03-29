package org.jetbrains.slackUnfurls.routing

import io.ktor.server.locations.*
import kotlinx.serialization.Serializable

object Routes {
    @Location("/api/homepage-data")
    object GetHomepageData

    @Location("/api/url-for-adding-slack-team")
    object UrlForAddingSlackTeam

    @Location("/api/remove-slack-team")
    data class RemoveSlackTeam(val slackTeamId: String)

    /** Space-facing application endpoint for all types of payload */
    @Location("/space/api")
    object SpaceApiEndpoint

    /**
     * Starts a new OAuth flow for authenticating Slack user in Space for providing unfurls for Space links in Slack.
     * Called when Slack user clicks on "Authenticate" button under the message with link to Space.
     * */
    @Location("/space/oauth")
    data class SpaceOAuth(val spaceOrgId: String, val slackTeamId: String, val slackUserId: String)

    /**
     * Callback for the end of OAuth flow in Space.
     * Is called by Space when a user is authenticated in Space to provide unfurls in Slack
     * */
    @Location("/space/oauth/callback")
    data class SpaceOAuthCallback(
        val state: String? = null,
        val code: String? = null,
        val error: String? = null,
        val error_description: String? = null
    )

    /**
     * Starts a new OAuth flow for authenticating Space user in Slack for providing unfurls for Slack links in Space.
     * Called when Space user clicks on "Authenticate" button under the message with link to Slack.
     *  */
    @Location("/slack/oauth")
    data class SlackOAuth(
        val spaceOrgId: String,
        val spaceUser: String,
        val slackTeamId: String,
        val backUrl: String? = null
    )

    /**
     * Callback for the end of OAuth flow in Slack
     * Is called by Slack either when the application is installed to a new Slack team,
     * or when a user is authenticated in Slack to provide unfurls in Space
     * */
    @Location("/slack/oauth/callback")
    data class SlackOAuthCallback(val state: String? = null, val code: String? = null)

    /** Is called by Slack to notify application of the new events (links for unfurls) */
    @Location("/slack/events")
    object SlackEvents

    /**
     * Is called by Slack to notify application about user clicking buttons in interactive messages
     * (invitations to authenticate)
     * */
    @Location("/slack/interactive")
    object SlackInteractive
}

@Serializable
data class HomepageDataResponse(
    val teams: List<SlackTeamOut>,
    val canManage: Boolean,
    val unapprovedPermissions: String?,
    val unapprovedUnfurlDomains: String?,
    val hasPermissionToApprove: Boolean,
)

@Serializable
data class SlackTeamOut(val id: String, val domain: String, val iconUrl: String?, val name: String)
