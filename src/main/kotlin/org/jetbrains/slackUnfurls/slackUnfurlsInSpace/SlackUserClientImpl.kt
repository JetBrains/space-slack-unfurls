package org.jetbrains.slackUnfurls.slackUnfurlsInSpace

import com.slack.api.methods.response.conversations.ConversationsInfoResponse
import com.slack.api.methods.response.users.UsersInfoResponse
import org.jetbrains.slackUnfurls.db
import org.jetbrains.slackUnfurls.decrypt
import org.jetbrains.slackUnfurls.encrypt
import org.jetbrains.slackUnfurls.storage.UserToken
import org.slf4j.Logger

/** Slack client for fetching data from Slack on behalf of the user, automatically refreshes token when it expires */

class SlackUserClientImpl(
    private val context: SpaceUserKey,
    accessToken: String,
    refreshToken: String,
    permissionScopes: String?,
    log: Logger
) : BaseSlackClient(accessToken, refreshToken, permissionScopes, log) {

    suspend fun fetchMessage(channelId: String, messageId: String) = fetch("fetching message") { accessToken ->
        slackApiClient.methods(accessToken).conversationsHistory {
            it.channel(channelId)
                .latest(messageIdToTs(messageId))
                .inclusive(true)
                .limit(1)
        }
    }

    suspend fun fetchThreadMessage(channelId: String, messageId: String, threadTs: String) =
        fetch("fetching thread message") { accessToken ->
            slackApiClient.methods(accessToken).conversationsReplies {
                it.channel(channelId)
                    .latest(threadTs)
                    .ts(messageIdToTs(messageId))
                    .inclusive(true)
                    .limit(1)
            }
        }

    suspend fun fetchUserName(userId: String) = fetch("fetching user name") { accessToken ->
        slackApiClient.methods(accessToken).usersInfo {
            it.user(userId)
        }
    }

    suspend fun fetchBotName(botId: String) = fetch("fetching bot name") { accessToken ->
        slackApiClient.methods(accessToken).botsInfo {
            it.bot(botId)
        }
    }

    suspend fun fetchTeamName(teamId: String) = fetch("fetching team name") { accessToken ->
        slackApiClient.methods(accessToken).teamInfo {
            it.team(teamId)
        }
    }

    suspend fun fetchChannelName(channelId: String) = fetch("fetching channel name") { accessToken ->
        slackApiClient.methods(accessToken).conversationsInfo {
            it.channel(channelId)
        }
    }

    suspend fun fetchUserGroups() = fetch("fetching user groups") { accessToken ->
        slackApiClient.methods(accessToken).usergroupsList { it }
    }


    override suspend fun reloadTokensFromDb(): Tokens? {
        return when (val tokens =
            db.slackUserTokens.get(context.spaceOrgId, context.spaceUserId, context.slackTeamId)) {
            is UserToken.Value ->
                Tokens(decrypt(tokens.accessToken), decrypt(tokens.refreshToken), tokens.permissionScopes)
            is UserToken.UnfurlsDisabled ->
                null
            null -> {
                log.info("No Slack refresh token found")
                null
            }
        }
    }

    override suspend fun updateTokensInDb(tokens: Tokens) {
        db.slackUserTokens.save(
            spaceOrgId = context.spaceOrgId,
            spaceUserId = context.spaceUserId,
            slackTeamId = context.slackTeamId,
            accessToken = encrypt(tokens.accessToken),
            refreshToken = encrypt(tokens.refreshToken),
            permissionScopes = tokens.permissionScopes
        )
    }

    override suspend fun onInvalidRefreshToken() {
        db.slackUserTokens.delete(context.spaceOrgId, context.spaceUserId, context.slackTeamId)
    }

    override suspend fun onInvalidAppCredentials() {
        db.slackTeams.delete(context.slackTeamId)
    }

    override suspend fun resetToken() {
        db.slackUserTokens.delete(context.spaceOrgId, context.spaceUserId, context.slackTeamId)
    }

    private fun messageIdToTs(messageId: String) =
        messageId.removePrefix("p").let { it.dropLast(6) + "." + it.drop(it.length - 6) }
}


fun UsersInfoResponse.userName() =
    user?.profile?.let {
        it.displayName?.takeUnless { it.isBlank() } ?: it.realName?.takeUnless { it.isBlank() }
    }

suspend fun ConversationsInfoResponse.channelLink(slackClient: SlackUserClientImpl, slackDomain: String, id: String) =
    if (channel.isIm)
        slackClient.fetchUserName(channel.user)?.userName()
            ?.let { "[DM with $it](https://$slackDomain.slack.com/archives/$id)" }
    else
        channel?.name?.let { "[#$it](https://$slackDomain.slack.com/archives/$id)" }

sealed class SlackUserClient {

    class Instance(val instance: SlackUserClientImpl) : SlackUserClient()

    object UnfurlsDisabled : SlackUserClient()

    companion object {
        suspend fun tryCreate(context: SpaceUserKey, log: Logger): SlackUserClient? =
            when (val tokens = db.slackUserTokens.get(
                spaceOrgId = context.spaceOrgId,
                spaceUserId = context.spaceUserId,
                slackTeamId = context.slackTeamId
            )) {
                is UserToken.Value ->
                    Instance(
                        SlackUserClientImpl(
                            context,
                            decrypt(tokens.accessToken),
                            decrypt(tokens.refreshToken),
                            tokens.permissionScopes,
                            log
                        )
                    )
                is UserToken.UnfurlsDisabled ->
                    UnfurlsDisabled
                else ->
                    null
            }
    }
}
