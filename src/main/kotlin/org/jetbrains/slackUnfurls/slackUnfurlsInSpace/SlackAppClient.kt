package org.jetbrains.slackUnfurls.slackUnfurlsInSpace

import com.slack.api.RequestConfigurator
import com.slack.api.methods.request.chat.ChatUnfurlRequest
import org.jetbrains.slackUnfurls.db
import org.jetbrains.slackUnfurls.decrypt
import org.jetbrains.slackUnfurls.encrypt
import org.slf4j.Logger

/** Slack client for talking to Slack on behalf of the application itself (for posting unfurl contents).
 *  Automatically refreshes token when it expires.
 *  */

class SlackAppClient private constructor(
    private val teamId: String,
    accessToken: String,
    refreshToken: String,
    log: Logger
) : BaseSlackClient(accessToken, refreshToken, log, logPrefix = "Slack team $teamId") {

    companion object {
        suspend fun tryCreate(teamId: String, log: Logger): SlackAppClient? =
            db.slackTeams.getById(teamId)?.let { team ->
                SlackAppClient(teamId, decrypt(team.appAccessToken), decrypt(team.appRefreshToken), log)
            }
    }

    suspend fun sendUnfurlsToSlack(builder: RequestConfigurator<ChatUnfurlRequest.ChatUnfurlRequestBuilder>) =
        fetch { accessToken ->
            slackApiClient.methods(accessToken).chatUnfurl(builder)
        }

    override suspend fun reloadTokensFromDb(): Tokens? {
        val team = db.slackTeams.getById(teamId)
        if (team == null) {
            log.info("$logPrefix - refresh token cannot be retrieved from storage")
            return null
        }

        return Tokens(decrypt(team.appAccessToken), decrypt(team.appRefreshToken))
    }

    override suspend fun updateTokensInDb(tokens: Tokens) {
        db.slackTeams.updateTokens(teamId, encrypt(tokens.accessToken), encrypt(tokens.refreshToken))
    }

    override suspend fun resetToken() {
        db.slackTeams.delete(teamId)
    }

    override suspend fun onInvalidRefreshToken() {
        resetToken()
    }

    override suspend fun onInvalidAppCredentials() {
        resetToken()
    }
}
