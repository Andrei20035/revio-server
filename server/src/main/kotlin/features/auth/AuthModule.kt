package com.revio.server.features.auth

import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.IAuthSessionDAO
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.auth.session.SessionService
import org.koin.dsl.module

val authModule = module {

    single<IAuthDAO> { AuthDAO() }
    single<IAuthSessionDAO> { AuthSessionDAO() }
    single { RefreshTokenGenerator() }
    single<ISessionService> { SessionService(get(), get()) }
    single<GoogleTokenVerifier> { GoogleTokenVerifierImpl() }
    single<IAuthService> { AuthService(get(), get(), get(), get()) }

    single {
        val secret = System.getenv("JWT_SECRET")
            ?: error("JWT_SECRET environment variable is not set")
        val issuer = System.getenv("JWT_ISSUER")
            ?: error("JWT_ISSUER environment variable is not set")
        val audience = System.getenv("JWT_AUDIENCE")
            ?: error("JWT_AUDIENCE environment variable is not set")
        JwtService(secret, issuer, audience)
    }

}
