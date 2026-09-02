package features.comment

import com.revio.server.features.notification.INotificationEventService
import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.INotificationPolicyService
import com.revio.server.features.notification.IUserDeviceDAO
import com.revio.server.features.notification.IUserNotificationPrefsDAO
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.scoring.IScoringService
import org.koin.dsl.module

val commentModule = module {
    single<ICommentDAO> { CommentDAO() }
    single<ICommentService> {
        CommentService(
            get(),
            get(),
            get<IPostDAO>(),
            get<IScoringService>(),
            get<INotificationEventService>(),
            get<IUserNotificationPrefsDAO>(),
            get<IUserDeviceDAO>(),
            get<INotificationOutboxDAO>(),
            notificationPolicyService = get<INotificationPolicyService>(),
        )
    }
}
