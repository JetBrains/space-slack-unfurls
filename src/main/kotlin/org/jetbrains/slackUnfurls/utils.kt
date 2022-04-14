package org.jetbrains.slackUnfurls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.Logger

inline fun <T> List<T>.filterWithLogging(log: Logger, message: String, predicate: (T) -> Boolean): List<T> =
    filter(predicate).also {
        if (it.size < this.size) {
            log.info("Skipping ${this.size - it.size} $message")
        }
    }

inline fun <T, R> List<T>.mapNotNullWithLogging(log: Logger, message: String, transform: (T) -> R?) : List<R> =
    this.mapNotNull(transform).also {
        if (it.size < this.size) {
            log.info("Skipping ${this.size - it.size} $message")
        }
    }

object MDCParams {
    const val SPACE_ORG = "space_org"
    const val SPACE_USER = "space_user"
    const val SLACK_TEAM = "slack_team"
    const val SLACK_USER = "slack_user"
}

suspend inline fun <T> withSpaceLogContext(
    spaceOrgId: String,
    spaceUserId: String,
    slackTeamId: String,
    vararg params: Pair<String, String>,
    noinline block: suspend CoroutineScope.() -> T
) =
    MDCParams
        .run { mapOf(SPACE_ORG to spaceOrgId, SPACE_USER to spaceUserId, SLACK_TEAM to slackTeamId) + params }
        .let { withContext(MDCContext(it), block) }

suspend inline fun <T> withSlackLogContext(
    slackTeamId: String,
    slackUserId: String,
    spaceOrgId: String,
    vararg params: Pair<String, String>,
    noinline block: suspend CoroutineScope.() -> T
) =
    MDCParams
        .run { mapOf(SLACK_TEAM to slackTeamId, SLACK_USER to slackUserId, SPACE_ORG to spaceOrgId) + params }
        .let { withContext(MDCContext(it), block) }

