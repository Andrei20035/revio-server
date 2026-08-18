package com.revio.server.features.announcement

import org.koin.dsl.module

val announcementModule = module {
    single<IUserAnnouncementDAO> { UserAnnouncementDAO() }
    single<IAnnouncementService> { AnnouncementService(get()) }
}
