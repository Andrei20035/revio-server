package com.revio.server.features.waitlist

import org.koin.dsl.module

val waitlistModule = module {
    single<IWaitlistDAO> { WaitlistDAO() }
    single<ISupabaseWaitlistClient> { SupabaseWaitlistClient() }
    single<IWaitlistSyncService> { WaitlistSyncService(get(), get()) }
    single<IWaitlistLookupService> { WaitlistLookupService(get(), get()) }
}
