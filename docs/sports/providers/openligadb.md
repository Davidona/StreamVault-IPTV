# OpenLigaDB adapter

The optional built-in adapter reads public football data from OpenLigaDB. It
requires no API key and exposes fixtures, match scores/status, and league
standings. It does not request team logos or any provider-supplied media.

## Setup

Add one to five league-season scopes, separated by commas, in the form
`shortcut:season` (for example `bl1:2026`). The adapter rejects duplicate,
malformed, or unsupported scopes. The connection check validates the first
configured league-season with OpenLigaDB's available-groups endpoint.

## Requests and cache

The adapter uses the documented available-groups, season matches, match detail,
and standings endpoints. It requests at most a 31-day event window per call,
filters the returned season response into that half-open UTC interval, and
limits results to 500 matches and 100 standings rows. Requests are serialized
through one app-process limiter with a minimum 1.2-second spacing, below the
provider's published 60 requests/minute/IP ceiling.

Only raw response JSON is cached, in process memory, for up to eight entries and
4 MiB total. Entries are scoped to the provider account, configuration revision,
and endpoint, and use short TTLs. No normalized or durable derivative database
is created. Team-logo URLs in match and standings records are ignored because
those images have separate rights.

## Attribution and release gate

OpenLigaDB data is identified as ODbL 1.0 data in the reviewed provider
descriptor. The app displays a linked OpenLigaDB source credit and linked ODbL
1.0 notice on provider event cards and standings. The provider remains hidden
under production policy unless the attribution surface is enabled in the
registry. Debug policy can expose it for local development.

This review does not test an account or establish rights beyond the public
terms reviewed on 2026-10-17. Re-review before adding persistent data storage,
logos, public exports, or any derivative database.

## References

- [OpenLigaDB API overview and ODbL statement](https://beta.openligadb.de/)
- [OpenLigaDB API reference](https://api.openligadb.de/index.html)
- [OpenLigaDB privacy/API use notes](https://beta.openligadb.de/Privacy)
- [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/)
