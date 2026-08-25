package com.revio.server.features.leaderboard

import org.koin.dsl.module

val leaderboardModule = module {
    single<ILeaderboardDAO> { LeaderboardDAO() }
    single<ILeaderboardSnapshotDAO> { LeaderboardSnapshotDAO() }
    single<ILeaderboardService> { LeaderboardService(get(), get(), get()) }
    single<ILeaderboardDeltaDAO> { LeaderboardDeltaDAO() }
    single<ILeaderboardDeltaService> { LeaderboardDeltaService(get(), get()) }
}
