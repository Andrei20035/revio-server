package com.revio.server.features.moderation

import com.revio.server.core.storage.IStorageService
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.notification.INotificationDAO
import com.revio.server.features.notification.INotificationService
import com.revio.server.features.post.IPostService
import org.koin.dsl.module

val moderationModule = module {
    single<IAdminAuditLogDAO> { AdminAuditLogDAO() }
    single<IModerationViolationDAO> { ModerationViolationDAO(get<IAdminAuditLogDAO>()) }
    single<IOrphanedStorageObjectDAO> { OrphanedStorageObjectDAO() }
    single<IAdminUserQueryDAO> { AdminUserQueryDAO() }
    single<IBanDAO> { BanDAO(get<IAdminAuditLogDAO>(), get<INotificationDAO>()) }
    single<IModerationService> {
        ModerationService(
            get<IPostService>(),
            get<INotificationService>(),
            get<IStorageService>(),
            get<IOrphanedStorageObjectDAO>(),
            get<IAdminUserQueryDAO>(),
            get<IModerationViolationDAO>(),
            get<IBanDAO>(),
            get<IAdminAuditLogDAO>(),
            get<ISessionService>(),
        )
    }
}
