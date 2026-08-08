package com.revio.server.features.notification

import org.koin.dsl.module

val notificationModule = module {
    single<INotificationDAO> { NotificationDAO() }
    single<INotificationService> { NotificationService(get()) }
}
