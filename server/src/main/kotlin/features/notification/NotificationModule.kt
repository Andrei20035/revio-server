package com.revio.server.features.notification

import org.koin.dsl.module

val notificationModule = module {
    single<INotificationDAO> { NotificationDAO() }
    single<INotificationService> { NotificationService(get()) }
    single<IUserDeviceDAO> { UserDeviceDAO() }
    single<IDeviceRegistryService> { DeviceRegistryService(get()) }
    single<IUserNotificationPrefsDAO> { UserNotificationPrefsDAO() }
    single<INotificationPrefsService> { NotificationPrefsService(get()) }
    single<IFcmCredentialsProvider> { FcmCredentialsProvider() }
    single<INotificationOutboxDAO> { NotificationOutboxDAO() }
    single<INotificationEventService> { NotificationEventService() }
    single<INotificationPolicyService> { NotificationPolicyService() }
    single<IPushDispatchService> { PushDispatchService(get()) }
    single<INotificationOutboxProcessor> { NotificationOutboxProcessor(get(), get(), get(), get(), get()) }
    single<IDiscoveryDAO> { DiscoveryDAO() }
    single<IDiscoveryJob> { DiscoveryJob(get(), get(), get(), get(), get(), get()) }
    single<IInactivityDAO> { InactivityDAO() }
    single<IInactivityJob> { InactivityJob(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single<IChallengeStartDAO> { ChallengeStartDAO() }
    single<IChallengeStartJob> { ChallengeStartJob(get(), get(), get(), get(), get(), get(), get(), get()) }
}
