package org.jetbrains.slackUnfurls.storage

import org.jetbrains.slackUnfurls.decrypt
import org.jetbrains.slackUnfurls.routing.Routes
import space.jetbrains.api.runtime.SpaceAppInstance

interface Storage {

    val slackTeams: SlackTeams

    val spaceOrgs: SpaceOrganizations

    val slackUserTokens: SlackUserTokens

    val slackOAuthSessions: SlackOAuthSessions

    val spaceUserTokens: SpaceUserTokens

    val spaceOAuthSessions: SpaceOAuthSessions

    val deferredSlackLinkUnfurlEvents: DeferredSlackLinkUnfurlEvents


    interface SlackTeams {
        suspend fun getById(teamId: String): SlackTeam?
        suspend fun getByDomain(domain: String): SlackTeam?
        suspend fun create(teamId: String, domain: String, accessToken: ByteArray, refreshToken: ByteArray)
        suspend fun updateDomain(teamId: String, newDomain: String)
        suspend fun updateTokens(teamId: String, accessToken: ByteArray, refreshToken: ByteArray?)
        suspend fun delete(teamId: String)
    }

    interface SpaceOrganizations {
        suspend fun save(spaceAppInstance: SpaceAppInstance)
        suspend fun getById(clientId: String): SpaceOrg?
        suspend fun getByDomain(domain: String): SpaceOrg?
        suspend fun updateLastUnfurlQueueItemEtag(clientId: String, lastEtag: Long?)
        suspend fun updateServerUrl(clientId: String, newServerUrl: String)
        suspend fun updateClientSecret(clientId: String, newClientSecret: String)
    }

    interface SlackUserTokens {
        suspend fun save(spaceOrgId: String, spaceUserId: String, slackTeamId: String, accessToken: ByteArray, refreshToken: ByteArray?)
        suspend fun get(spaceOrgId: String, spaceUserId: String, slackTeamId: String): UserToken?
        suspend fun delete(spaceOrgId: String, spaceUserId: String, slackTeamId: String)
        suspend fun disableUnfurls(spaceOrgId: String, spaceUserId: String, slackTeamId: String)
    }

    interface SlackOAuthSessions {
        suspend fun create(id: String, params: Routes.SlackOAuth)
        suspend fun getOnce(id: String): SlackOAuthSession?
    }

    interface SpaceUserTokens {
        suspend fun save(slackTeamId: String, slackUserId: String, spaceOrgId: String, accessToken: ByteArray, refreshToken: ByteArray)
        suspend fun get(key: SlackUserKey): UserToken?
        suspend fun delete(slackTeamId: String, slackUserId: String, spaceOrgId: String)
        suspend fun disableUnfurls(slackTeamId: String, slackUserId: String, spaceOrgId: String)
    }

    interface SpaceOAuthSessions {
        suspend fun create(id: String, params: Routes.SpaceOAuth)
        suspend fun getOnce(id: String): SpaceOAuthSession?
    }

    interface DeferredSlackLinkUnfurlEvents {
        suspend fun create(slackTeamId: String, slackUserId: String, spaceOrgId: String, event: String)
        suspend fun getOnce(slackTeamId: String, slackUserId: String, spaceOrgId: String, limit: Int): List<String>
    }
}

class SlackTeam(val id: String, val domain: String, val appAccessToken: ByteArray, val appRefreshToken: ByteArray)

class SpaceOrg(val url: String, val domain: String, val clientId: String, val clientSecret: ByteArray, val lastUnfurlQueueItemEtag: Long?)

data class SlackUserKey(val spaceOrgId: String, val slackTeamId: String, val slackUserId: String)

fun SpaceOrg.toSpaceAppInstance() =
    SpaceAppInstance(clientId = clientId, clientSecret = decrypt(clientSecret), spaceServerUrl = url)

class SlackOAuthSession(val spaceOrgId: String, val spaceUserId: String, val slackTeamId: String, val backUrl: String?)

sealed class UserToken {
    object UnfurlsDisabled: UserToken()
    class Value(val accessToken: ByteArray, val refreshToken: ByteArray): UserToken()
}

class SpaceOAuthSession(val slackTeamId: String, val slackUserId: String, val spaceOrgId: String, val backUrl: String?)
