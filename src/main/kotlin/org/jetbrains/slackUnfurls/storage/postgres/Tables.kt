package org.jetbrains.slackUnfurls.storage.postgres

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.datetime

val allTables = listOf(
    SpaceOrganizations,
    SlackTeams,
    Slack2Space,
    SlackOAuthSessions,
    SlackOAuthUserTokens,
    SpaceOAuthSessions,
    SpaceOAuthUserTokens,
    DeferredSlackLinkUnfurlEvents
)

object SpaceOrganizations : Table("SpaceOrganizations") {
    val clientId = varchar("clientId", 36)
    val domain = varchar("domain", 256).uniqueIndex()
    val clientSecret = blob("clientSecret")
    val orgUrl = varchar("orgUrl", 256)
    val lastUnfurlQueueItemEtag = long("lastUnfurlQueueItemEtag").nullable()
    val created = datetime("created")

    override val primaryKey = PrimaryKey(clientId)
}

object SlackTeams : IdTable<String>("SlackTeams") {
    override val id = varchar("id", 100).entityId()
    override val primaryKey = PrimaryKey(id)

    val domain = varchar("domain", 256).uniqueIndex()
    val accessToken = blob("accessToken")
    val refreshToken = blob("refreshToken")
    val created = datetime("created")
    val iconUrl = varchar("iconUrl", 256).nullable()
    val name = varchar("name", 256).nullable()
}

object Slack2Space : Table("Slack2Space") {
    val spaceOrgId = varchar("spaceOrgId", 36).references(SpaceOrganizations.clientId, onDelete = ReferenceOption.CASCADE)
    val slackTeamId = varchar("slackTeamId", 100).references(SlackTeams.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(spaceOrgId, slackTeamId)

    init {
        uniqueIndex(slackTeamId, spaceOrgId)
    }
}

object SlackOAuthSessions : IdTable<String>("SlackOAuthSessions") {
    override val id: Column<EntityID<String>> = char("id", 16).entityId()
    override val primaryKey = PrimaryKey(id)

    val spaceOrgId = varchar("spaceOrgId", 36).references(SpaceOrganizations.clientId, onDelete = ReferenceOption.CASCADE)
    val spaceUserId = varchar("spaceUserId", 20)
    val slackTeamId = varchar("slackTeamId", 100).references(SlackTeams.id, onDelete = ReferenceOption.CASCADE)
    val backUrl = varchar("backUrl", 1024).nullable()
    val permissionScopes = text("permissionScopes").nullable()
    val created = datetime("created").nullable()

    init {
        uniqueIndex(spaceOrgId, spaceUserId, slackTeamId)
        index(isUnique = false, created)
    }
}

object SlackOAuthUserTokens : Table("SlackOAuthUserTokens") {
    val spaceOrgId = varchar("spaceOrgId", 36).references(SpaceOrganizations.clientId, onDelete = ReferenceOption.CASCADE)
    val spaceUserId = varchar("spaceUserId", 20)
    val slackTeamId = varchar("slackTeamId", 100).references(SlackTeams.id, onDelete = ReferenceOption.CASCADE)
    val accessToken = blob("accessToken").nullable()
    val refreshToken = blob("refreshToken").nullable()
    val permissionScopes = text("permissionScopes").nullable()
    val unfurlsDisabled = bool("unfurlsDisabled")

    init {
        uniqueIndex(spaceOrgId, spaceUserId, slackTeamId)
    }

    fun SqlExpressionBuilder.by(spaceOrgId: String, spaceUserId: String, slackTeamId: String): Op<Boolean> {
        return (SlackOAuthUserTokens.spaceOrgId eq spaceOrgId) and
                (SlackOAuthUserTokens.spaceUserId eq spaceUserId) and
                (SlackOAuthUserTokens.slackTeamId eq slackTeamId)
    }
}

object SpaceOAuthSessions : IdTable<String>("SpaceOAuthSessions") {
    override val id: Column<EntityID<String>> = char("id", 16).entityId()
    override val primaryKey = PrimaryKey(id)

    val slackTeamId = varchar("slackTeamId", 100).references(SlackTeams.id, onDelete = ReferenceOption.CASCADE)
    val slackUserId = varchar("slackUserId", 256)
    val spaceOrgId = varchar("spaceOrgId", 36).references(SpaceOrganizations.clientId, onDelete = ReferenceOption.CASCADE)
    val backUrl = varchar("oAuthBackUrl", 1024).nullable()
    val permissionScopes = text("permissionScopes").nullable()
    val created = datetime("created").nullable()

    init {
        uniqueIndex(slackTeamId, slackUserId, spaceOrgId)
        index(isUnique = false, created)
    }
}

object SpaceOAuthUserTokens : Table("SpaceOAuthUserTokens") {
    val slackTeamId = varchar("slackTeamId", 100).references(SlackTeams.id, onDelete = ReferenceOption.CASCADE)
    val slackUserId = varchar("slackUserId", 256)
    val spaceOrgId = varchar("spaceOrgId", 36).references(SpaceOrganizations.clientId, onDelete = ReferenceOption.CASCADE)
    val unfurlsDisabled = bool("unfurlsDisabled")
    val accessToken = blob("accessToken").nullable()
    val refreshToken = blob("refreshToken").nullable()
    val permissionScopes = text("permissionScopes").nullable()

    init {
        uniqueIndex(slackTeamId, slackUserId, spaceOrgId)
    }

    fun SqlExpressionBuilder.by(slackTeamId: String, slackUserId: String, spaceOrgId: String): Op<Boolean> {
        return (SpaceOAuthUserTokens.slackTeamId eq slackTeamId) and
                (SpaceOAuthUserTokens.slackUserId eq slackUserId) and
                (SpaceOAuthUserTokens.spaceOrgId eq spaceOrgId)
    }
}

object DeferredSlackLinkUnfurlEvents : Table("DeferredSlackLinkUnfurlEvents") {
    val slackTeamId = varchar("slackTeamId", 100).references(SlackTeams.id, onDelete = ReferenceOption.CASCADE)
    val slackUserId = varchar("slackUserId", 256)
    val spaceOrgId = varchar("spaceOrgId", 256).references(SpaceOrganizations.clientId, onDelete = ReferenceOption.CASCADE)
    val event = text("event")

    init {
        index("ix_slackteam_slackuser_spaceorgid", isUnique = false, slackTeamId, slackUserId, spaceOrgId)
    }
}
