package org.jetbrains.slackUnfurls.spaceUnfurlsInSlack.unfurlProviders

import com.slack.api.methods.request.chat.ChatUnfurlRequest
import com.slack.api.model.kotlin_extension.block.withBlocks
import io.ktor.http.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.resources.richText
import space.jetbrains.api.runtime.types.*
import java.time.format.DateTimeFormatter


object ChatUnfurlProvider : SpaceUnfurlProvider {

    override val matchers = listOf(
        Regex("/im/([A-Z0-9.-_@/]*)", RegexOption.IGNORE_CASE) to ChatUnfurlProvider::matchByChannelId
    )

    override val spacePermissionScopes = listOf(
        "global:Channel.ViewMessages",
        "global:Channel.ViewChannel",
        "global:Article.View",
        "global:Article.Comments.View"
    )

    private suspend fun matchByChannelId(
        url: Url,
        match: MatchResult,
        spaceClient: SpaceClient
    ): ChatUnfurlRequest.UnfurlDetail? {
        val contactKey = match.groups[1]?.value ?: return null
        val (channelId, messageId) = url.parameters["channel"] to url.parameters["message"]
        val channelIdentifier = if (channelId != null) ChannelIdentifier.Id(channelId) else ChannelIdentifier.ContactKey(contactKey)

        if (messageId != null) {
            return provideMessageUnfurl(url, channelIdentifier, messageId, spaceClient)
        }

        val channel = spaceClient.chats.channels.getChannel(channelIdentifier) {
            contact {
                ext {
                    name()
                }
                key()
                defaultName()
            }
        }
        val channelName = (channel.contact.ext as? M2SharedChannelContent)?.name ?: channel.contact.defaultName

        return ChatUnfurlRequest.UnfurlDetail().apply {
            blocks = withBlocks {
                context {
                    spaceLogo()
                    markdownText("<$url|$channelName> in JetBrains Space")
                }
            }
        }
    }

    suspend fun provideMessageUnfurl(url: Url, channelIdentifier: ChannelIdentifier, messageId: String, spaceClient: SpaceClient): ChatUnfurlRequest.UnfurlDetail? {
        val channel = spaceClient.chats.channels.getChannel(channelIdentifier) {
            contact {
                ext {
                    name()
                }
                key()
                defaultName()
            }
        }
        val channelUrl =
            url.copy(encodedPath = "/im/${channel.contact.key}", parameters = Parameters.Empty, fragment = "")
        val channelName = (channel.contact.ext as? M2SharedChannelContent)?.name ?: channel.contact.defaultName

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

        val messageText = buildString {
            val spaceRichText = spaceClient.richText.parseMarkdown(message.text)
            appendDocument(spaceRichText)
        }

        return ChatUnfurlRequest.UnfurlDetail().apply {
            blocks = withBlocks {
                context {
                    markdownText("*$authorName* in <$channelUrl|$channelName> ($createdAt)")
                }
                section {
                    markdownText(messageText)
                }
                context {
                    spaceLogo()
                    markdownText("<$url|View message>")
                }
            }
        }
    }

    private fun StringBuilder.appendDocument(doc: RtDocument) {
        doc.children.forEach { appendBlockNode(it, linePrefix = "") }
    }

    private fun StringBuilder.appendBlockNode(node: BlockNode, linePrefix: String, prefixForFirstLine: Boolean = false) {
        fun appendWithLinePrefix(s: String, ix: Int) =
            append("${if (ix > 0 || prefixForFirstLine) linePrefix else ""}$s")

        when (node) {
            is RtBlockquote -> {
                node.children.forEachIndexed { ix, child ->
                    appendWithLinePrefix("> ", ix)
                    appendBlockNode(child, "$linePrefix\t")
                }
                if (linePrefix.isEmpty())
                    appendLine()
            }
            is RtBulletList -> {
                if (linePrefix.isEmpty())
                    appendLine()
                node.children.forEachIndexed { ix, child ->
                    appendWithLinePrefix("*  ", ix)
                    appendBlockNode(child, "$linePrefix\t")
                }
                if (linePrefix.isEmpty())
                    appendLine()
            }
            is RtOrderedList -> {
                if (linePrefix.isEmpty())
                    appendLine()
                node.children.forEachIndexed { ix, item ->
                    appendWithLinePrefix("${ix + node.startNumber}.  ", ix)
                    appendBlockNode(item, "$linePrefix\t")
                }
                if (linePrefix.isEmpty())
                    appendLine()
            }
            is RtListItem -> {
                node.children.forEachIndexed { ix, child ->
                    appendBlockNode(child, linePrefix, prefixForFirstLine || ix > 0)
                }
            }
            is RtCode -> {
                appendLine("```")
                node.children.forEach {
                    appendInlineNode(it)
                    appendLine()
                }
                appendLine("```")
                appendLine()
            }
            is RtHeading -> {
                if (prefixForFirstLine) {
                    append(linePrefix)
                }
                node.children.forEach { appendInlineNode(it) }
                appendLine()
            }
            is RtParagraph -> {
                if (prefixForFirstLine) {
                    append(linePrefix)
                }
                node.children.forEach { appendInlineNode(it) }
                appendLine()
            }
        }
    }

    private fun StringBuilder.appendInlineNode(node: InlineNode) {
        when (node) {
            is RtBreak ->
                appendLine()
            is RtImage -> {}
            is RtText -> {
                node.marks.forEach { openMark(it) }
                append(node.value)
                node.marks.forEach { closeMark(it) }
            }
        }
    }

    private fun StringBuilder.openMark(mark: DocumentMark) {
        when (mark) {
            is RtStrikeThroughMark ->
                append("~")
            is RtLinkMark ->
                if (mark.attrs.details.let { it is RtTeamLinkDetails || it is RtProfileLinkDetails || it is RtPredefinedMentionLinkDetails })
                    append("@")
                else
                    append("<${mark.attrs.href}|")
            is RtItalicMark ->
                append("_")
            is RtCodeMark ->
                append("`")
            is RtBoldMark ->
                append("*")
        }
    }

    private fun StringBuilder.closeMark(mark: DocumentMark) {
        when (mark) {
            is RtStrikeThroughMark ->
                append("~")
            is RtLinkMark ->
                if (!mark.attrs.details.let { it is RtTeamLinkDetails || it is RtProfileLinkDetails || it is RtPredefinedMentionLinkDetails })
                    append(">")
            is RtItalicMark ->
                append("_")
            is RtCodeMark ->
                append("`")
            is RtBoldMark ->
                append("*")
        }
    }
}
