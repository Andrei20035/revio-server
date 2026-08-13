package com.revio.server.features.challenge

import com.revio.server.features.car_family.ICarFamilyDAO
import org.koin.dsl.module

val challengeModule = module {
    single<IChallengeDAO> { ChallengeDAO() }
    single<IChallengeProgressDAO> { ChallengeProgressDAO() }
    single<IChallengeService> { ChallengeService(get(), get(), get<ICarFamilyDAO>()) }
    single<IChallengeProgressService> { ChallengeProgressService(get(), get()) }
    single<IChallengeFinalizationService> { ChallengeFinalizationService(get(), get()) }
}
