package org.jetbrains.slackUnfurls.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import io.ktor.config.*
import io.ktor.http.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.slackUnfurls.config
import org.jetbrains.slackUnfurls.encrypt
import org.jetbrains.slackUnfurls.routing.Routes
import org.jetbrains.slackUnfurls.storage.*
import space.jetbrains.api.runtime.SpaceAppInstance
import java.time.LocalDateTime

class PostgresStorage(private val db: Database) : Storage {

    override val slackTeams = object : Storage.SlackTeams {
        override suspend fun getById(teamId: String): SlackTeam? {
            return tx {
                SlackTeams.select { SlackTeams.id eq teamId }.firstOrNull()?.toSlackTeam()
            }
        }

        override suspend fun getByDomain(domain: String): SlackTeam? {
            return tx {
                SlackTeams.select { SlackTeams.domain eq domain }.firstOrNull()?.toSlackTeam()
            }
        }

        override suspend fun updateDomain(teamId: String, newDomain: String) {
            tx {
                SlackTeams.update(
                    where = { SlackTeams.id eq teamId },
                    body = { it[this.domain] = newDomain }
                )
            }
        }

        override suspend fun updateTokens(teamId: String, accessToken: ByteArray, refreshToken: ByteArray?) {
            tx {
                SlackTeams.update(
                    where = { SlackTeams.id eq teamId },
                    body = {
                        it[this.accessToken] = ExposedBlob(accessToken)
                        if (refreshToken != null) {
                            it[this.refreshToken] = ExposedBlob(refreshToken)
                        }
                    }
                )
            }
        }

        override suspend fun create(teamId: String, domain: String, accessToken: ByteArray, refreshToken: ByteArray) {
            tx {
                SlackTeams.deleteWhere { SlackTeams.id eq teamId }
                SlackTeams.insert {
                    it[this.id] = teamId
                    it[this.domain] = domain
                    it[this.created] = LocalDateTime.now()
                    it[this.accessToken] = ExposedBlob(accessToken)
                    it[this.refreshToken] = ExposedBlob(refreshToken)
                }
            }
        }

        override suspend fun delete(teamId: String) {
            tx {
                SlackTeams.deleteWhere { SlackTeams.id eq teamId }
            }
        }


        private fun ResultRow.toSlackTeam() =
            SlackTeam(
                id = this[SlackTeams.id].value,
                domain = this[SlackTeams.domain],
                appAccessToken = this[SlackTeams.accessToken].bytes,
                appRefreshToken = this[SlackTeams.refreshToken].bytes
            )
    }

    override val spaceOrgs = object : Storage.SpaceOrganizations {
        override suspend fun save(spaceAppInstance: SpaceAppInstance) {
            tx {
                val domain = Url(spaceAppInstance.spaceServer.serverUrl).host
                SpaceOrganizations.deleteWhere {
                    (SpaceOrganizations.clientId eq spaceAppInstance.clientId) or (SpaceOrganizations.domain eq domain)
                }
                SpaceOrganizations.insert {
                    it[created] = LocalDateTime.now()
                    it[clientId] = spaceAppInstance.clientId
                    it[clientSecret] = ExposedBlob(encrypt(spaceAppInstance.clientSecret))
                    it[orgUrl] = spaceAppInstance.spaceServer.serverUrl
                    it[this.domain] = domain
                }
            }
        }

        override suspend fun getById(clientId: String): SpaceOrg? {
            return tx {
                SpaceOrganizations
                    .select { SpaceOrganizations.clientId eq clientId }
                    .map { it.toSpaceOrg() }
                    .firstOrNull()
            }
        }

        override suspend fun getByDomain(domain: String): SpaceOrg? {
            return tx {
                SpaceOrganizations
                    .select { SpaceOrganizations.domain eq domain }
                    .map { it.toSpaceOrg() }
                    .firstOrNull()
            }
        }

        override suspend fun updateLastUnfurlQueueItemEtag(clientId: String, lastEtag: Long?) {
            tx {
                SpaceOrganizations.update(
                    where = { SpaceOrganizations.clientId eq clientId },
                    body = { it[lastUnfurlQueueItemEtag] = lastEtag }
                )
            }
        }

        override suspend fun updateServerUrl(clientId: String, newServerUrl: String) {
            tx {
                SpaceOrganizations.update(
                    where = { SpaceOrganizations.clientId eq clientId },
                    body = {
                        it[orgUrl] = newServerUrl
                        it[domain] = Url(newServerUrl).host
                    }
                )
            }
        }

        override suspend fun updateClientSecret(clientId: String, newClientSecret: String) {
            tx {
                SpaceOrganizations.update(
                    where = { SpaceOrganizations.clientId eq clientId },
                    body = {
                        it[clientSecret] = ExposedBlob(encrypt(newClientSecret))
                    }
                )
            }
        }

        private fun ResultRow.toSpaceOrg() =
            SpaceOrg(
                url = this[SpaceOrganizations.orgUrl],
                domain = this[SpaceOrganizations.domain],
                clientId = this[SpaceOrganizations.clientId],
                clientSecret = this[SpaceOrganizations.clientSecret].bytes,
                lastUnfurlQueueItemEtag = this[SpaceOrganizations.lastUnfurlQueueItemEtag]
            )
    }

    override val slackUserTokens = object : Storage.SlackUserTokens {
        override suspend fun save(
            spaceOrgId: String,
            spaceUserId: String,
            slackTeamId: String,
            accessToken: ByteArray,
            refreshToken: ByteArray?,
            permissionScopes: String?
        ) {
            tx {
                val updated = SlackOAuthUserTokens.update(
                    where = {
                        with(SlackOAuthUserTokens) { by(spaceOrgId, spaceUserId, slackTeamId) }
                    },
                    body = {
                        it[SlackOAuthUserTokens.permissionScopes] = permissionScopes
                        it[SlackOAuthUserTokens.accessToken] = ExposedBlob(accessToken)
                        if (refreshToken != null) {
                            it[this.refreshToken] = ExposedBlob(refreshToken)
                        }
                    }
                )

                if (updated == 0) {
                    if (refreshToken == null)
                        error("Refresh token is missing for newly created Slack user token")

                    SlackOAuthUserTokens.insert {
                        it[this.spaceOrgId] = spaceOrgId
                        it[this.spaceUserId] = spaceUserId
                        it[this.slackTeamId] = slackTeamId
                        it[this.accessToken] = ExposedBlob(accessToken)
                        it[this.refreshToken] = ExposedBlob(refreshToken)
                        it[this.permissionScopes] = permissionScopes
                        it[this.unfurlsDisabled] = false
                    }
                }
            }
        }

        override suspend fun get(spaceOrgId: String, spaceUserId: String, slackTeamId: String): UserToken? {
            return tx {
                SlackOAuthUserTokens
                    .select {
                        with(SlackOAuthUserTokens) {
                            by(spaceOrgId, spaceUserId, slackTeamId)
                        }
                    }
                    .firstOrNull()
                    ?.let {
                        if (it[SlackOAuthUserTokens.unfurlsDisabled])
                            UserToken.UnfurlsDisabled
                        else {
                            val accessToken = it[SlackOAuthUserTokens.accessToken]
                            val refreshToken = it[SlackOAuthUserTokens.refreshToken]
                            val permissionScopes = it[SlackOAuthUserTokens.permissionScopes]
                            if (accessToken != null && refreshToken != null)
                                UserToken.Value(accessToken.bytes, refreshToken.bytes, permissionScopes)
                            else
                                null
                        }
                    }
            }
        }

        override suspend fun delete(spaceOrgId: String, spaceUserId: String, slackTeamId: String) {
            tx {
                SlackOAuthUserTokens.deleteWhere {
                    with(SlackOAuthUserTokens) {
                        by(spaceOrgId = spaceOrgId, spaceUserId = spaceUserId, slackTeamId = slackTeamId)
                    }
                }
            }
        }

        override suspend fun disableUnfurls(spaceOrgId: String, spaceUserId: String, slackTeamId: String) {
            tx {
                val updated = SlackOAuthUserTokens.update(
                    where = {
                        with(SlackOAuthUserTokens) {
                            by(spaceOrgId = spaceOrgId, spaceUserId = spaceUserId, slackTeamId = slackTeamId)
                        }
                    },
                    body = {
                        it[unfurlsDisabled] = true
                        it[refreshToken] = null
                    }
                )
                if (updated == 0) {
                    SlackOAuthUserTokens.insert {
                        it[this.spaceOrgId] = spaceOrgId
                        it[this.spaceUserId] = spaceUserId
                        it[this.slackTeamId] = slackTeamId
                        it[unfurlsDisabled] = true
                        it[refreshToken] = null
                    }
                }
            }
        }
    }

    override val slackOAuthSessions = object : Storage.SlackOAuthSessions {
        override suspend fun create(id: String, params: Routes.SlackOAuth, permissionScopes: String) {
            tx {
                SlackOAuthSessions.run {
                    deleteWhere {
                        (spaceOrgId eq params.spaceOrgId) and (spaceUserId eq params.spaceUser) and (slackTeamId eq params.slackTeamId)
                    }
                    SlackOAuthSessions.insert {
                        it[this.id] = id
                        it[this.spaceOrgId] = params.spaceOrgId
                        it[this.spaceUserId] = params.spaceUser
                        it[this.slackTeamId] = params.slackTeamId
                        it[this.backUrl] = params.backUrl
                        it[this.permissionScopes] = permissionScopes
                    }
                }
            }
        }

        override suspend fun getOnce(id: String): SlackOAuthSession? {
            return tx {
                SlackOAuthSessions.select { SlackOAuthSessions.id eq id }.firstOrNull()
                    ?.let { session ->
                        SlackOAuthSessions.deleteWhere { SlackOAuthSessions.id eq id }
                        SlackOAuthSession(
                            spaceOrgId = session[SlackOAuthSessions.spaceOrgId],
                            spaceUserId = session[SlackOAuthSessions.spaceUserId],
                            slackTeamId = session[SlackOAuthSessions.slackTeamId],
                            backUrl = session[SlackOAuthSessions.backUrl],
                            permissionScopes = session[SlackOAuthSessions.permissionScopes]
                        )
                    }
            }
        }
    }

    override val spaceUserTokens = object : Storage.SpaceUserTokens {
        override suspend fun save(
            slackTeamId: String,
            slackUserId: String,
            spaceOrgId: String,
            accessToken: ByteArray,
            refreshToken: ByteArray,
            permissionScopes: String?
        ) {
            tx {
                with (SpaceOAuthUserTokens) {
                    deleteWhere {
                        by(slackTeamId = slackTeamId, slackUserId = slackUserId, spaceOrgId = spaceOrgId)
                    }
                }
                SpaceOAuthUserTokens.insert {
                    it[this.slackTeamId] = slackTeamId
                    it[this.slackUserId] = slackUserId
                    it[this.spaceOrgId] = spaceOrgId
                    it[this.accessToken] = ExposedBlob(accessToken)
                    it[this.refreshToken] = ExposedBlob(refreshToken)
                    it[this.permissionScopes] = permissionScopes
                    it[this.unfurlsDisabled] = false
                }
            }
        }

        override suspend fun get(key: SlackUserKey): UserToken? {
            return tx {
                SpaceOAuthUserTokens
                    .select {
                        with (SpaceOAuthUserTokens) { by(key.slackTeamId, key.slackUserId, key.spaceOrgId) }
                    }
                    .firstOrNull()
                    ?.let {
                        if (it[SpaceOAuthUserTokens.unfurlsDisabled])
                            UserToken.UnfurlsDisabled
                        else {
                            val accessToken = it[SpaceOAuthUserTokens.accessToken]
                            val refreshToken = it[SpaceOAuthUserTokens.refreshToken]
                            val permissionScopes = it[SpaceOAuthUserTokens.permissionScopes]
                            if (accessToken != null && refreshToken != null)
                                UserToken.Value(accessToken.bytes, refreshToken.bytes, permissionScopes)
                            else
                                null
                        }
                    }
            }
        }

        override suspend fun delete(slackTeamId: String, slackUserId: String, spaceOrgId: String) {
            tx {
                with (SpaceOAuthUserTokens) {
                    deleteWhere {
                        by(slackTeamId = slackTeamId, slackUserId = slackUserId, spaceOrgId = spaceOrgId)
                    }
                }
            }
        }

        override suspend fun disableUnfurls(slackTeamId: String, slackUserId: String, spaceOrgId: String) {
            tx {
                SpaceOAuthUserTokens.deleteWhere {
                    with(SpaceOAuthUserTokens) {
                        by(slackTeamId = slackTeamId, slackUserId = slackUserId, spaceOrgId = spaceOrgId)
                    }
                }
                SpaceOAuthUserTokens.insert {
                    it[this.slackTeamId] = slackTeamId
                    it[this.slackUserId] = slackUserId
                    it[this.spaceOrgId] = spaceOrgId
                    it[this.unfurlsDisabled] = true
                }
            }
        }
    }

    override val spaceOAuthSessions = object : Storage.SpaceOAuthSessions {
        override suspend fun create(id: String, params: Routes.SpaceOAuth, permissionScopes: String) {
            tx {
                SpaceOAuthSessions.run {
                    run {
                        deleteWhere {
                            (slackTeamId eq params.slackTeamId) and (slackUserId eq params.slackUserId) and (spaceOrgId eq params.spaceOrgId)
                        }
                    }
                    insert {
                        it[this.id] = id
                        it[slackTeamId] = params.slackTeamId
                        it[slackUserId] = params.slackUserId
                        it[spaceOrgId] = params.spaceOrgId
                        it[this.permissionScopes] = permissionScopes
                    }
                }
            }
        }

        override suspend fun getOnce(id: String): SpaceOAuthSession? {
            return tx {
                SpaceOAuthSessions.select { SpaceOAuthSessions.id eq id }.firstOrNull()
                    ?.let { session ->
                        SpaceOAuthSessions.deleteWhere { SpaceOAuthSessions.id eq id }
                        SpaceOAuthSession(
                            slackTeamId = session[SpaceOAuthSessions.slackTeamId],
                            slackUserId = session[SpaceOAuthSessions.slackUserId],
                            spaceOrgId = session[SpaceOAuthSessions.spaceOrgId],
                            backUrl = session[SpaceOAuthSessions.backUrl],
                            permissionScopes = session[SpaceOAuthSessions.permissionScopes]
                        )
                    }
            }
        }
    }

    override val deferredSlackLinkUnfurlEvents = object : Storage.DeferredSlackLinkUnfurlEvents {
        override suspend fun create(slackTeamId: String, slackUserId: String, spaceOrgId: String, event: String) {
            tx {
                DeferredSlackLinkUnfurlEvents.insert {
                    it[this.slackTeamId] = slackTeamId
                    it[this.slackUserId] = slackUserId
                    it[this.spaceOrgId] = spaceOrgId
                    it[this.event] = event
                }
            }
        }

        override suspend fun getOnce(slackTeamId: String, slackUserId: String, spaceOrgId: String, limit: Int): List<String> {
            fun SqlExpressionBuilder.filter() = (DeferredSlackLinkUnfurlEvents.slackTeamId eq slackTeamId) and
                    (DeferredSlackLinkUnfurlEvents.slackUserId eq slackUserId) and
                    (DeferredSlackLinkUnfurlEvents.spaceOrgId eq spaceOrgId)

            return tx {
                val events = DeferredSlackLinkUnfurlEvents
                    .select { filter() }
                    .limit(limit)
                    .map { it[DeferredSlackLinkUnfurlEvents.event] }

                DeferredSlackLinkUnfurlEvents.deleteWhere { filter() }

                events
            }
        }
    }


    private fun <T> tx(statement: Transaction.() -> T): T =
        transaction(db, statement)
}

fun initPostgres() : PostgresStorage? {
    val postgresUrl = config.tryGetString("storage.postgres.url")?.let { Url(it) } ?: return null

    val dataSource = object : HikariDataSource() {
        init {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = postgresUrl
                .copy(protocol = URLProtocol("jdbc:postgresql", postgresUrl.port), user = null, password = null)
                .toString()
            username = postgresUrl.user
            password = postgresUrl.password
        }
    }

    val connection = Database.connect(dataSource)

    transaction(connection) {
        SchemaUtils.createMissingTablesAndColumns(*allTables.toTypedArray())
    }

    return PostgresStorage(connection)
}
