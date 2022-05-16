package org.jetbrains.slackUnfurls.slackUnfurlsInSpace

import com.slack.api.Slack
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.SlackApiTextResponse
import org.jetbrains.slackUnfurls.SlackCredentials
import org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.slackErrorsToResetToken
import org.slf4j.Logger

abstract class BaseSlackClient(
    accessToken: String,
    refreshToken: String,
    permissionScopes: String?,
    protected val log: Logger
) {

    private var tokens: Tokens? = Tokens(accessToken, refreshToken, permissionScopes)

    protected suspend fun <T : SlackApiTextResponse> fetch(action: String, handler: suspend (String) -> T?) : T? {
        return tokens?.let {
            try {
                handler(it.accessToken).let { response: T? ->
                    if (response != null && !response.isOk) {
                        handleSlackError(action, response.error, handler)
                    } else {
                        response
                    }
                }
            } catch (ex: SlackApiException) {
                log.error("Failure fetching data from Slack", ex)
                handleSlackError(action, ex.error.error, handler)
            }
        }
    }

    private suspend fun <T : SlackApiTextResponse> handleSlackError(action: String, error: String, handler: (suspend (String) -> T?)?) : T? {
        if ((error == "token_expired" || error == "cannot_auth_user" || error == "invalid_auth") && handler != null) {
            tryRefreshToken()
            // break recursion on the second call to `handleSlackError` by omitting handler parameter
            return tokens?.let {
                try {
                    handler(it.accessToken).let { response: T? ->
                        if (response != null && !response.isOk) {
                            handleSlackError(action, response.error, handler = null)
                        } else {
                            response
                        }
                    }
                } catch (ex: SlackApiException) {
                    log.error("Failure fetching data from Slack", ex)
                    handleSlackError(action, ex.error.error, handler = null)
                }
            }
        }

        val shouldResetToken = slackErrorsToResetToken.contains(error)
        val slackUserTokenResetMessage = if (shouldResetToken) "Slack user refresh token is reset." else ""
        log.warn("Got ok=false from Slack on $action - $error. $slackUserTokenResetMessage")
        if (shouldResetToken) {
            resetToken()
        }
        return null
    }

    private suspend fun tryRefreshToken() {
        log.info("Refreshing token...")
        val refreshToken = tokens?.refreshToken ?: return
        val permissionScopes = tokens?.permissionScopes
        tokens = null

        val response = slackApiClient.methods().oauthV2Access {
            it
                .clientId(SlackCredentials.clientId)
                .clientSecret(SlackCredentials.clientSecret)
                .grantType("refresh_token")
                .refreshToken(refreshToken)
        }
        if (!response.isOk) {
            log.warn("Got ok=false while trying to refresh access token - ${response.error}")
            if (response.error == "invalid_refresh_token") {
                val tokensFromDb = reloadTokensFromDb() ?: return
                if (tokensFromDb.refreshToken != refreshToken) {
                    tokens = tokensFromDb
                } else {
                    onInvalidRefreshToken()
                }
            }
            if (response.error == "invalid_client_id" || response.error == "bad_client_secret") {
                onInvalidAppCredentials()
            }
            return
        }

        val newAccessToken = response.accessToken ?: response.authedUser?.accessToken
        if (newAccessToken == null) {
            log.warn("Got ok response from Slack but no access token provided")
            return
        }

        val newRefreshToken =
            (response.refreshToken ?: response.authedUser?.refreshToken).takeUnless { it == refreshToken }
        log.info("Access token refreshed, ${if (newRefreshToken != null) "with" else "without"} new refresh token. Permission scope = ${response.authedUser?.scope}")
        tokens = Tokens(
            newAccessToken,
            refreshToken = newRefreshToken ?: refreshToken,
            permissionScopes = response.authedUser?.scope ?: permissionScopes
        ).also {
            updateTokensInDb(it)
        }
    }


    protected abstract suspend fun reloadTokensFromDb(): Tokens?

    protected abstract suspend fun updateTokensInDb(tokens: Tokens)

    protected abstract suspend fun onInvalidRefreshToken()

    protected abstract suspend fun onInvalidAppCredentials()

    protected abstract suspend fun resetToken()


    protected data class Tokens(val accessToken: String, val refreshToken: String, val permissionScopes: String? = null)
}

val slackApiClient: Slack = Slack.getInstance()
