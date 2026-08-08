package com.revio.server.features.post

import com.revio.server.features.car_model.ICarModelDAO
import com.revio.server.features.challenge.IChallengeProgressDAO
import com.revio.server.features.challenge.IChallengeProgressService
import com.revio.server.features.moderation.IAdminAuditLogDAO
import com.revio.server.features.moderation.IModerationViolationDAO
import com.revio.server.features.moderation.IOrphanedStorageObjectDAO
import com.revio.server.features.notification.INotificationService
import com.revio.server.features.scoring.IScoringDao
import com.revio.server.features.scoring.IScoringService
import features.comment.ICommentDAO
import features.like.ILikeDAO
import org.koin.dsl.module

val postModule = module {
    single<IPostDAO> { PostDAO() }
    single<IPostRemovalDAO> {
        PostRemovalDAO(
            get<IChallengeProgressDAO>(),
            get<IScoringDao>(),
            get<IModerationViolationDAO>(),
            get<IAdminAuditLogDAO>(),
            get<INotificationService>(),
        )
    }
    single<IPostService> {
        PostServiceImpl(
            get(),
            get(),
            get<ICarModelDAO>(),
            get<ILikeDAO>(),
            get<ICommentDAO>(),
            get<IScoringService>(),
            get<IScoringDao>(),
            get<IChallengeProgressService>(),
            get<IPostRemovalDAO>(),
            get<IOrphanedStorageObjectDAO>(),
        )
    }
}
