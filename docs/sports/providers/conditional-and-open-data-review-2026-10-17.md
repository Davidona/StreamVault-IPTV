# Plan 4 provider-gate review — 2026-10-17

This is a review of published terms and documentation, not legal advice or a
license grant. No provider was contacted.

## Conditional providers

| Provider | Published review | Release gate | Adapter |
|---|---|---|---|
| API-SPORTS | Terms say API-SPORTS does not license publication of its data in a user's app/site/product and reserve relevant competition rights to leagues, federations, and organizers. | `DO_NOT_SHIP` unless the necessary publication permissions are separately established. | None |
| SportDB.dev | Production use requires a paid plan; terms prohibit API resale/sublicensing and place downstream third-party rights on the user, but do not explicitly answer direct display in a paid native app. | `REQUIRES_VENDOR_CONFIRMATION`; production use also requires the user's paid plan and any relevant third-party permissions. | None |
| SportDevs | Official API docs link to `https://sportdevs.com/terms`, but that page returned HTTP 502 when reviewed. The API docs alone do not establish publication terms. | `DO_NOT_SHIP` pending a successful terms review. | None |

Official sources:

- [API-SPORTS terms](https://api-sports.io/terms)
- [SportDB.dev terms](https://sportdb.dev/tos.html)
- [SportDB.dev API and product overview](https://sportdb.dev/)
- [SportDevs official docs and linked terms](https://docs.sportdevs.com/docs/football/sportdevs)
- [SportDevs terms page](https://sportdevs.com/terms)

## Enterprise adapters

SportsDataIO and Sportradar remain deferred. This task contains no customer
request, production contract, or property-specific grant for either provider.
Their Phase 4.9 requirements remain contract-specific; no adapter or production
data path was added.

## OpenLigaDB / ODbL

OpenLigaDB states that its API data are under ODbL 1.0. The official API
documentation describes unauthenticated fixture, match-detail, and table
routes, asks clients to poll sparingly, and states a limit of 60 requests per
minute per IP. The official privacy page says linked team/league images remain
subject to their own club/federation rights, so a StreamVault adapter must not
request or display them.

ODbL 1.0 section 4.3 requires a visible notice for a publicly used Produced
Work identifying the source database and its ODbL availability; the example
uses links to the database and license. Section 4.5(b) says creating a Produced
Work from the original database does not itself create a derivative database.
If an adapter creates or publicly uses a derivative database, sections 4.4
and 4.6 add share-alike and machine-readable access obligations.

Implementation constraints for the optional adapter:

- show the source and ODbL notice with links on every OpenLigaDB-produced event
  and standings surface;
- fetch only documented API responses, use a descriptive application
  `User-Agent`, honor the per-IP limit, and avoid frequent full-season polling;
- do not fetch linked logos or persist/merge a normalized derivative database;
- if caching is needed, keep the provider's raw response in a short-lived
  process-local cache and retain its source/license provenance.

The adapter now follows these constraints. It requests only the documented
group, season-match, match-detail, and standings routes. A singleton process
limiter spaces calls by 1.2 seconds (at most 50 requests per minute), while a
cache scoped to provider instance and configuration revision retains only up
to eight raw JSON responses/4 MiB for short TTLs. It does not persist a
normalized or merged database. Fixture events and standings display the source
and ODbL links; event data never reads team-logo URLs. Offline MockWebServer
fixtures validate the mapping and gating. No live account was used.

ODbL and provider references:

- [OpenLigaDB API overview and ODbL statement](https://beta.openligadb.de/)
- [OpenLigaDB API reference](https://api.openligadb.de/index.html)
- [OpenLigaDB privacy/API use notes](https://beta.openligadb.de/Privacy)
- [Open Database License 1.0 full text](https://opendatacommons.org/licenses/odbl/1-0/)
- [ODbL summary](https://opendatacommons.org/licenses/odbl/summary/)

This review records an implementation choice based on the cited public terms
and API documentation; it is not a legal opinion. Re-review the terms before
adding persistence, team logos, another data surface, or a derivative database.
