package com.revio.server.features.moderation

import kotlinx.serialization.Serializable

/**
 * Why an admin removed a post. The enum names are part of the wire contract and must stay
 * identical to the Android client's moderation reason picker.
 */
@Serializable
enum class ModerationReason {
    NO_CAR_CONTENT,
    SEXUAL_CONTENT,
    VIOLENT_CONTENT,
    HATE_SPEECH,
    HARASSMENT,
    SPAM_OR_MISLEADING,
    UNAUTHORIZED_ADVERTISING,
    ILLEGAL_ACTIVITY,
    PERSONAL_INFORMATION,
    COPYRIGHT_INFRINGEMENT,
    FAKE_OR_STOLEN_CONTENT,
    LOW_QUALITY,
    OTHER,
}

/**
 * The English label inserted into the "Your post was removed. Reason: [label]." notification.
 * Server-side single source of truth so the wording stays identical everywhere it's used.
 */
val ModerationReason.label: String
    get() = when (this) {
        ModerationReason.NO_CAR_CONTENT -> "No car-related content"
        ModerationReason.SEXUAL_CONTENT -> "Sexual or explicit content"
        ModerationReason.VIOLENT_CONTENT -> "Violence or disturbing content"
        ModerationReason.HATE_SPEECH -> "Hate speech or discrimination"
        ModerationReason.HARASSMENT -> "Harassment or bullying"
        ModerationReason.SPAM_OR_MISLEADING -> "Spam or misleading content"
        ModerationReason.UNAUTHORIZED_ADVERTISING -> "Unauthorized advertising"
        ModerationReason.ILLEGAL_ACTIVITY -> "Illegal or dangerous activity"
        ModerationReason.PERSONAL_INFORMATION -> "Personal information shared"
        ModerationReason.COPYRIGHT_INFRINGEMENT -> "Copyright infringement"
        ModerationReason.FAKE_OR_STOLEN_CONTENT -> "Fake or stolen content"
        ModerationReason.LOW_QUALITY -> "Low-quality or irrelevant content"
        ModerationReason.OTHER -> "Other"
    }
