package org.jetbrains.slackUnfurls.slackUnfurlsInSpace

import org.jetbrains.slackUnfurls.db
import org.jetbrains.slackUnfurls.decrypt
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.helpers.SpaceAppInstanceStorage

val spaceAppInstanceStorage = object : SpaceAppInstanceStorage {

    override suspend fun loadAppInstance(clientId: String): SpaceAppInstance? {
        return db.spaceOrgs.getById(clientId)?.let {
            SpaceAppInstance(
                clientId = it.clientId,
                clientSecret = decrypt(it.clientSecret),
                spaceServerUrl = it.url
            )
        }
    }

    override suspend fun saveAppInstance(appInstance: SpaceAppInstance) {
        db.spaceOrgs.save(appInstance)
    }
}
