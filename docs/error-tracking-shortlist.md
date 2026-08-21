# Shortlist și criterii: error tracking pentru server (Ktor/JVM)

Pas 0.4 din planul de observabilitate (closed testing). Doar shortlist + criterii de selecție —
**nu se alege un vendor**, nu se instalează nimic. Decizia finală e un pas ulterior, separat.

## De ce nu Crashlytics (verificat, H19 din analiza inițială)

Documentația oficială Firebase (*Get started with Crashlytics*) listează exclusiv platforme
client: iOS, Android, Flutter, Unity, Android NDK. Nu există SDK sau ghid pentru backend
JVM/Ktor. Crashlytics e legat de un "Firebase App" identificat prin bundle/package name de
mobil — conceptul nu se aplică unui proces server. **Exclus din start, fără alternativă.**

## Stare actuală a infrastructurii server (verificat, motivează criteriile)

- Deployment: **un singur VPS Hetzner**, `server/docker-compose.yml` — două containere azi
  (`postgres`, `app`), fără niciun container/agent de observabilitate existent.
- Zero infrastructură de metrici/tracing azi: grep pentru `micrometer|prometheus|opentelemetry|
  sentry|logstash|datadog|newrelic` în `build.gradle.kts`/`libs.versions.toml` → 0 rezultate
  (H16, confirmat).
- Logging azi: `ConsoleAppender` simplu în `logback.xml`, fără agregator extern.
- Pas 0.2 (decis): consent opt-in global, piețe de closed testing **neconfirmate**, deci nu se
  poate exclude prezența unor date ale unor utilizatori din UE/UK → rezidența datelor contează.
- Server rulează Ktor 3.1.3 / Kotlin 2.1.20 — orice soluție trebuie să aibă SDK JVM matur,
  nu doar generic-HTTP.

## Criterii de selecție (pentru decizia ulterioară)

1. **Suport JVM/Kotlin/Ktor nativ** — SDK dedicat, nu doar un client HTTP generic de integrat manual.
2. **Compatibil cu un singur VPS mic** — fără dependență de un cluster Kubernetes sau de o
   infrastructură de colectare separată (agent greu, sidecar-uri multiple). Amprenta de
   memorie/CPU trebuie să încapă lângă `app` + `postgres` pe același VPS, sau soluția trebuie
   să fie externă (SaaS) fără agent local greu.
3. **Rezidența datelor / GDPR** — dat fiind 0.2 (opt-in global, piețe neconfirmate), soluția
   trebuie fie să ofere găzduire în UE, fie să fie self-hosted (rezidența devine automat cea a
   VPS-ului Hetzner).
4. **Cost previzibil la volum mic** — closed testing înseamnă un număr mic de utilizatori;
   soluția nu trebuie să oblige la un plan plătit de la prima excepție.
5. **Corelare cu `callId`** (pas 3.1/3.7, neimplementat încă) — capacitate de a atașa un tag/context
   arbitrar (id de request) pe fiecare excepție raportată, pentru corelarea client↔server.
6. **Efort de integrare compatibil cu pașii deja planificați** — se conectează firesc la
   handler-ul global din `config/StatusPages.kt` (pas 3.3a) fără refactor mare.
7. **Grupare/deduplicare a excepțiilor** — altfel un bug repetat (ex. cel din C5, risc de
   duplicate la creare de postare) generează zgomot, nu semnal.

## Shortlist — două categorii, nu un vendor

### Categoria A — self-hosted, pe VPS-ul existent

Rulează ca un container suplimentar în `docker-compose.yml`, alături de `app` și `postgres`.
Avantaj: rezidența datelor rezolvată automat (rămân pe Hetzner), fără cost de licență.
Dezavantaj: consum suplimentar de resurse pe un VPS deja mic, întreținere proprie (upgrade,
backup, expunere publică a unui nou serviciu).

Exemple reprezentative ale categoriei (neevaluate exhaustiv, doar ca reper de scară):
GlitchTip (open-source, compatibil cu SDK-ul Sentry), sau un stack minimal
Grafana Loki + Alertmanager peste logurile structurate deja planificate la pasul 3.2.

### Categoria B — SaaS extern, cu SDK JVM

Fără container suplimentar pe VPS; excepțiile pleacă spre un serviciu extern. Avantaj: zero
întreținere de infrastructură, grupare/deduplicare matură din prima zi. Dezavantaj: rezidența
datelor depinde de regiunea serviciului (trebuie verificată explicit dacă oferă găzduire UE),
cost care poate crește cu volumul dincolo de closed testing.

Exemple reprezentative ale categoriei (neevaluate exhaustiv): servicii de tip Sentry (SaaS,
cu regiune UE disponibilă) sau alte platforme echivalente de error tracking cu SDK Kotlin/JVM.

## Ce NU decide acest document

- Nu alege între Categoria A și B.
- Nu alege un produs anume în interiorul categoriei alese.
- Nu instalează nimic, nu adaugă dependințe în `build.gradle.kts`.
- Decizia finală (pas ulterior, în afara scopului 0.4) trebuie să evalueze explicit criteriul 3
  (rezidența datelor) pentru orice opțiune SaaS candidată, înainte de a fi aleasă.
