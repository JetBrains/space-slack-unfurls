package org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders

import com.slack.api.methods.request.chat.ChatUnfurlRequest
import com.slack.api.model.kotlin_extension.block.withBlocks
import io.ktor.http.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.types.CUserPrincipalDetails
import space.jetbrains.api.runtime.types.ChannelIdentifier
import space.jetbrains.api.runtime.types.ChatMessageIdentifier
import space.jetbrains.api.runtime.types.M2SharedChannelContent
import java.time.format.DateTimeFormatter


object ChatUnfurlProvider : SpaceUnfurlProvider {

    override val matchers = listOf(
        Regex("/im/([A-Z0-9.-_@/]*)", RegexOption.IGNORE_CASE) to ChatUnfurlProvider::matchByChannelId
    )

    override val spacePermissionScopes = listOf(
        "global:Channel.ViewMessages",
        "global:Channel.ViewChannel",
        "global:Profile.DirectMessages.ReadMessages"
    )

    private suspend fun matchByChannelId(
        url: Url,
        match: MatchResult,
        spaceClient: SpaceClient
    ): ChatUnfurlRequest.UnfurlDetail? {
        val contactKey = match.groups[1]?.value ?: return null
        val (channelId, messageId) = url.parameters["channel"] to url.parameters["message"]

        val channelIdentifier = if (channelId != null) ChannelIdentifier.Id(channelId) else ChannelIdentifier.ContactKey(contactKey)
        val channel = spaceClient.chats.channels.getChannel(channelIdentifier) {
            contact {
                ext {
                    name()
                }
                key()
                defaultName()
            }
        }
        val channelUrl = url.copy(encodedPath = "/im/${channel.contact.key}", parameters = Parameters.Empty, fragment = "")
        val channelName = (channel.contact.ext as? M2SharedChannelContent)?.name ?: channel.contact.defaultName

        return if (messageId != null) {
            val message = spaceClient.chats.messages.getMessage(
                ChatMessageIdentifier.InternalId(messageId),
                channelIdentifier
            ) {
                author {
                    details {
                        user {
                            name {
                                firstName()
                                lastName()
                            }
                        }
                    }
                    name()
                }
                created()
                text()
            }

            val authorName = (message.author.details as? CUserPrincipalDetails)?.user?.name?.run { "$firstName $lastName" }
                ?: message.author.name

            val createdAt = message.created.toLocalDateTime(TimeZone.currentSystemDefault())
                .toJavaLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

            ChatUnfurlRequest.UnfurlDetail().apply {
                blocks = withBlocks {
                    context {
                        markdownText("*$authorName* in <$channelUrl|$channelName> ($createdAt)")
                    }
                    section {
                        markdownText(message.text)
                    }
                    context {
                        spaceLogo()
                        markdownText("<$url|View message>")
                    }
                }
            }
        } else {
            ChatUnfurlRequest.UnfurlDetail().apply {
                blocks = withBlocks {
                    context {
                        spaceLogo()
                        markdownText("<$url|$channelName> in JetBrains Space")
                    }
                }
            }
        }
    }
}
