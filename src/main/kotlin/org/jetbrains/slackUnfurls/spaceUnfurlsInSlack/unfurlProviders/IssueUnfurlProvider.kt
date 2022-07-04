package org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders

import com.slack.api.methods.request.chat.ChatUnfurlRequest
import com.slack.api.model.kotlin_extension.block.withBlocks
import io.ktor.http.*
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import space.jetbrains.api.runtime.types.partials.IssuePartial


object IssueUnfurlProvider : SpaceUnfurlProvider {

    override val matchers = listOf(
        Regex("/p/${PROJECT_KEY_REGEX.pattern}/issues/(\\d+)", RegexOption.IGNORE_CASE) to IssueUnfurlProvider::matchByDirectLink,
        Regex("/im/issue/([A-Z0-9]*)", RegexOption.IGNORE_CASE) to IssueUnfurlProvider::matchByChannelId
    )

    override val spacePermissionScopes = listOf("global:Project.Issues.View")

    private suspend fun matchByDirectLink(
        url: Url,
        match: MatchResult,
        spaceClient: SpaceClient
    ): ChatUnfurlRequest.UnfurlDetail? {

        val projectKey = match.groups[1]?.value?.uppercase()
        val issueNumber = match.groups[2]?.value?.toIntOrNull()

        if (projectKey == null || issueNumber == null)
            return null

        val issueKey = IssueIdentifier.Key("$projectKey-T-$issueNumber")
        val issue = spaceClient.projects.planning.issues.getIssue(ProjectIdentifier.Key(projectKey), issueKey) { issueFields() }
        return buildUnfurl(url, projectKey, issue)
    }

    private suspend fun matchByChannelId(
        url: Url,
        match: MatchResult,
        spaceClient: SpaceClient
    ): ChatUnfurlRequest.UnfurlDetail? {
        if (url.parameters["message"] != null)
            return null

        val issueId = match.groups[1]?.value ?: return null
        val channel = spaceClient.chats.channels.getChannel(ChannelIdentifier.Issue(IssueIdentifier.Id(issueId))) {
            content {
                projectKey()
                issue {
                    issueFields()
                }
            }
        }

        val issueChannel = channel.content as? M2ChannelIssueInfo
        val projectKey = issueChannel?.projectKey
        if (issueChannel == null || projectKey == null)
            return null

        return buildUnfurl(url, projectKey.key.uppercase(), issueChannel.issue)
    }

    private fun IssuePartial.issueFields() {
        number()
        description()
        title()
    }

    private fun buildUnfurl(url: Url, projectKey: String, issue: Issue) : ChatUnfurlRequest.UnfurlDetail {
        return ChatUnfurlRequest.UnfurlDetail().apply {
            blocks = withBlocks {
                section {
                    markdownText("<$url|*$projectKey-T-${issue.number}* ${issue.title}>")
                }
                issue.description?.let {
                    section {
                        markdownText(it.limitTo(3000))
                    }
                }
                context {
                    spaceLogo()
                    val projectUrl = URLBuilder(url).apply {
                        encodedPath = "/p/$projectKey"
                        encodedParameters = ParametersBuilder()
                        fragment = ""
                    }.build()
                    markdownText("JetBrains Space issue in <$projectUrl|$projectKey> project")
                }
            }
        }
    }
}