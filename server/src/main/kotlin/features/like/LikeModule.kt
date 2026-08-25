package features.like

import com.revio.server.features.notification.INotificationEventService
import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.IUserDeviceDAO
import com.revio.server.features.notification.IUserNotificationPrefsDAO
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.scoring.IScoringService
import com.revio.server.features.user.IUserDAO
import org.koin.dsl.module

val likeModule = module {
    single<ILikeDAO> { LikeDAO() }
    single<ILikeNotificationCursorDAO> { LikeNotificationCursorDAO() }
    single<ILikeService> {
        LikeService(
            get(),
            get<IPostDAO>(),
            get<IScoringService>(),
            get<INotificationEventService>(),
            get<IUserDeviceDAO>(),
            get<INotificationOutboxDAO>(),
            get<ILikeNotificationCursorDAO>(),
            get<IUserDAO>(),
            get<IUserNotificationPrefsDAO>(),
        )
    }
}