package org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders

import com.slack.api.methods.request.chat.ChatUnfurlRequest
import com.slack.api.model.kotlin_extension.block.withBlocks
import io.ktor.http.*
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.types.*
import space.jetbrains.api.runtime.types.partials.CodeReviewRecordPartial


object CodeReviewUnfurlProvider : SpaceUnfurlProvider {

    override val matchers = listOf(
        Regex("/p/${PROJECT_KEY_REGEX.pattern}/reviews/(\\d+)(/.*)?", RegexOption.IGNORE_CASE) to CodeReviewUnfurlProvider::matchByDirectLink,
        Regex("/im/review/([A-Z0-9]*)", RegexOption.IGNORE_CASE) to CodeReviewUnfurlProvider::matchByChannelId
    )

    override val spacePermissionScopes = listOf(
        "global:Project.CodeReview.View",
        "global:Project.CodeReview.ViewComments",
        "global:VcsRepository.Read"
    )

    private suspend fun matchByDirectLink(
        url: Url,
        match: MatchResult,
        spaceClient: SpaceClient
    ): ChatUnfurlRequest.UnfurlDetail? {

        val projectKey = match.groups[1]?.value?.uppercase()
        val reviewNumber = match.groups[2]?.value?.toIntOrNull()

        if (projectKey == null || reviewNumber == null)
            return null

        val review = spaceClient.projects.codeReviews.getCodeReview(
            ProjectIdentifier.Key(projectKey),
            ReviewIdentifier.Number(reviewNumber)
        ) {
            codeReviewFields()
        } ?: return null

        return buildUnfurl(url, review)
    }

    private suspend fun matchByChannelId(
        url: Url,
        match: MatchResult,
        spaceClient: SpaceClient
    ): ChatUnfurlRequest.UnfurlDetail? {
        val reviewId = match.groups[1]?.value ?: return null
        val channelIdentifier = ChannelIdentifier.Review(ReviewIdentifier.Id(reviewId))

        url.parameters["message"]?.let { messageId ->
            return ChatUnfurlProvider.provideMessageUnfurl(url, channelIdentifier, messageId, spaceClient)
        }

        val channel = spaceClient.chats.channels.getChannel(channelIdentifier) {
            content {
                project {
                    key()
                }
                codeReview {
                    codeReviewFields()
                }
            }
        }

        val reviewChannel = channel.content as? M2ChannelContentCodeReviewFeed ?: return null
        val (review, projectKey) = reviewChannel.let { it.codeReview to it.project?.key }
        if (review == null || projectKey == null)
            return null

        return buildUnfurl(url, review)
    }

    private fun CodeReviewRecordPartial.codeReviewFields() {
        title()
        state()
        number()
        project {
            key()
        }
        createdBy {
            name {
                firstName()
                lastName()
            }
        }
    }

    private fun buildUnfurl(url: Url, review: CodeReviewRecord): ChatUnfurlRequest.UnfurlDetail {
        return ChatUnfurlRequest.UnfurlDetail().apply {
            blocks = withBlocks {
                when (review) {
                    is CommitSetReviewRecord -> {
                        section {
                            markdownText("<$url|*${review.project.key}-CR-${review.number}* ${review.title}>")
                        }
                        context {
                            review.createdBy?.name?.let {
                                markdownText("Authored by *${it.firstName} ${it.lastName}*, ${review.state}")
                            }
                        }
                        context {
                            spaceLogo()
                            val projectUrl = URLBuilder(url).apply {
                                encodedPath = "/p/${review.project.key}"
                                encodedParameters = ParametersBuilder()
                                fragment = ""
                            }.build()
                            markdownText("JetBrains Space code review in <$projectUrl|${review.project.key}> project")
                        }
                    }

                    is MergeRequestRecord -> {
                        section {
                            markdownText("<$url|*${review.project.key}-MR-${review.number}* ${review.title}>")
                        }
                        context {
                            review.createdBy?.name?.let {
                                markdownText("${review.state}, authored by *${it.firstName} ${it.lastName}*")
                            } ?: run {
                                markdownText("${review.state}")
                            }
                        }
                        context {
                            spaceLogo()
                            val projectUrl = URLBuilder(url).apply {
                                encodedPath = "/p/${review.project.key}"
                                encodedParameters = ParametersBuilder()
                                fragment = ""
                            }.build()
                            markdownText("JetBrains Space merge request in <$projectUrl|${review.project.key}> project")
                        }
                    }
                }
            }
        }
    }
}