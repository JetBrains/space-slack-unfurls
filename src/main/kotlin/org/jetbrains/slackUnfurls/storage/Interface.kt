package org.jetbrains.slackUnfurls.storage

import io.ktor.server.application.*
import kotlinx.coroutines.Job
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
        suspend fun getForSpaceOrg(spaceOrgId: String): List<SlackTeam>
        suspend fun getById(teamId: String, spaceOrgId: String? = null): SlackTeam?
        suspend fun getByDomain(domain: String, spaceOrgId: String): SlackTeam?
        suspend fun create(teamId: String, domain: String, spaceOrgId: String, accessToken: ByteArray, refreshToken: ByteArray, iconUrl: String?, name: String)
        suspend fun updateDomain(teamId: String, newDomain: String)
        suspend fun updateIconUrlAndName(teamId: String, iconUrl: String, name: String)
        suspend fun updateTokens(teamId: String, accessToken: ByteArray, refreshToken: ByteArray?)
        suspend fun disconnectFromSpaceOrg(teamId: String, spaceOrgId: String)
        suspend fun delete(teamId: String)
    }

    interface SpaceOrganizations {
        suspend fun save(spaceAppInstance: SpaceAppInstance)
        suspend fun getById(clientId: String, slackTeamId: String? = null): SpaceOrg?
        suspend fun getByDomain(domain: String, slackTeamId: String): SpaceOrg?
        suspend fun updateLastUnfurlQueueItemEtag(clientId: String, lastEtag: Long?)
    }

    interface SlackUserTokens {
        suspend fun save(
            spaceOrgId: String,
            spaceUserId: String,
            slackTeamId: String,
            accessToken: ByteArray,
            refreshToken: ByteArray?,
            permissionScopes: String?
        )
        suspend fun get(spaceOrgId: String, spaceUserId: String, slackTeamId: String): UserToken?
        suspend fun delete(spaceOrgId: String, spaceUserId: String, slackTeamId: String)
        suspend fun disableUnfurls(spaceOrgId: String, spaceUserId: String, slackTeamId: String)
    }

    interface SlackOAuthSessions {
        suspend fun create(id: String, params: Routes.SlackOAuth, permissionScopes: String)
        suspend fun get(id: String): SlackOAuthSession?
        fun Application.launchCleanup(): Job
    }

    interface SpaceUserTokens {
        suspend fun save(
            slackTeamId: String,
            slackUserId: String,
            spaceOrgId: String,
            accessToken: ByteArray,
            refreshToken: ByteArray,
            permissionScopes: String?
        )
        suspend fun get(key: SlackUserKey): UserToken?
        suspend fun delete(slackTeamId: String, slackUserId: String, spaceOrgId: String)
        suspend fun disableUnfurls(slackTeamId: String, slackUserId: String, spaceOrgId: String)
    }

    interface SpaceOAuthSessions {
        suspend fun create(id: String, params: Routes.SpaceOAuth, permissionScopes: String)
        suspend fun get(id: String): SpaceOAuthSession?
        fun Application.launchCleanup(): Job
    }

    interface DeferredSlackLinkUnfurlEvents {
        suspend fun create(slackTeamId: String, slackUserId: String, spaceOrgId: String, event: String)
        suspend fun getOnce(slackTeamId: String, slackUserId: String, spaceOrgId: String, limit: Int): List<String>
    }
}

class SlackTeam(val id: String, val domain: String, val appAccessToken: ByteArray, val appRefreshToken: ByteArray, val iconUrl: String?, val name: String?)

class SpaceOrg(val url: String, val domain: String, val clientId: String, val clientSecret: ByteArray, val lastUnfurlQueueItemEtag: Long?)

data class SlackUserKey(val spaceOrgId: String, val slackTeamId: String, val slackUserId: String)

fun SpaceOrg.toSpaceAppInstance() =
    SpaceAppInstance(clientId = clientId, clientSecret = decrypt(clientSecret), spaceServerUrl = url)

class SlackOAuthSession(
    val spaceOrgId: String,
    val spaceUserId: String,
    val slackTeamId: String,
    val backUrl: String?,
    val permissionScopes: String?
)

sealed class UserToken {
    object UnfurlsDisabled: UserToken()
    class Value(val accessToken: ByteArray, val refreshToken: ByteArray, val permissionScopes: String?): UserToken()
}

class SpaceOAuthSession(
    val slackTeamId: String,
    val slackUserId: String,
    val spaceOrgId: String,
    val backUrl: String?,
    val permissionScopes: String?
)
