package org.jetbrains.slackUnfurls

enum class AuthAction(val id: String) {
    Authenticate("authenticate"),
    NotNow("not-now"),
    Never("never")
}