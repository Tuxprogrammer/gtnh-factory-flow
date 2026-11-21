# wire

What the game's mod and the website say to each other, written as data and run against both ends. The files were
written from both ends' code. Where the ends disagreed, the server decided unless its own tests showed it wrong
(`findings.json` lists each case and what was done).

| File           | What it is                                                                                         |
| -------------- | -------------------------------------------------------------------------------------------------- |
| `shapes.json`  | The shape of every JSON body and every live frame (the language below).                            |
| `rest.json`    | Every request the mod makes of the site and the answer it reads, in order. Edited by hand.         |
| `plan.json`    | The two plans the steps send (`{plan}`, `{emptyPlan}`).                                            |
| `live.json`    | The live service: the upgrade (`connect`), the close codes (`close`), conversations (`scripts`), rules (`order`). |
| `findings.json`| Where the ends disagreed, the decision, and the tests that now pin it.                             |

## Who runs it

| Runner                                                    | Against                                                          |
| --------------------------------------------------------- | ---------------------------------------------------------------- |
| `src/lib/server/wire-rest.test.ts`                        | the real route handlers, in process, local database mode         |
| `src/live/wire-live.test.ts`                              | the real live service, in process, on loopback                   |
| `jvm/mod` `cloud/WireRestTest`                            | the mod's `CommunityApi` and `CloudApi`, over a canned HTTP site |
| `jvm/mod` `cloud/WireLiveTest`                            | the mod's `LiveSocket` and `LiveClient`, over a scripted service |
| `src/test-support/wire-mocks.test.ts`                     | the mod's dev stand-ins (`tools/dev/mock-library.mjs`), as a child process, over HTTP and WebSocket |

A step the stand-ins cannot play (a rate limit, an upload over the limit, datasets, a proxy's answer, state only the
real service holds) is listed in the run with its reason. A step carries `mock.skip` to say the same on its own.

## The shape language

A shape is a string or a JSON value.

- Primitives: `string`, `number`, `integer`, `boolean`, `null`, `object`, `array`, `any`.
- `A|B` is either. `$Name` is the shape of that name. `[S]` is an array whose items are all `S`.
- An object lists its required keys. A key ending in `?` is optional. Keys the shape does not name are allowed.
- `{"=": v}` is exactly `v`. `{"in": [...]}` is one of the listed values.

`expect` maps a dotted path (`plans.0.name`) to the value it must have. `{name}` in any string is filled by the runner
(`{ann.id}`, `{ann.token}`, the other accounts, `{post.id}`, `{dataset.id}`, `{plan}`, `{emptyPlan}`).

## A REST step

`id`, `client` (the mod method), `args`, `as` (the account, or null for none), `request` (`method`, `path`, `auth`
`session` or `none`, `body`, `bodyShape`, `bodyExpect`), `status`, `shape`, `example` (what the mod's run is answered
with), `expect` (what must be true of the answer; `serverExpect` replaces it for the server and the stand-ins, whose
state is fuller), `headers`, `result` (what the mod makes of it: `ok`, `null`, `refused` with the site's text,
`ioerror`), `capture` (an answer's field kept as a variable), `before` and `prime` (state a server run sets up),
`joins` (a two-request call), `server` (`skip`, `feedback`, `dataset`), `mock` (`skip`), `note`.

A step may depend only on what an earlier step gave. A test pins that.

## The live file

- `connect`: one row per kind of upgrade (cookie, origin, path), what the service answers, and what the mod makes of
  it (`mod.state`, `reason`, `retries`).
- `close`: every close code the service sends and what the mod does on it.
- `scripts`: conversations. A step is `client` or `server`. A client step has `via` (`open`, `join:<doc>`,
  `leave:<doc>`, `ping`, `presence:<doc>`, `ops:<doc>`) or a literal `frame`. A server step has a `shape`, an
  `example`, `expect`, and `heard` (what the mod's listener must have been given, keyed by frame type).
- `order`: rules of sequence, each with the tests that pin it.

## Where the mod sends credentials, and what the site requires

The session is the cookie `gtnh_session=<token>`. The token is `<accountId>.<expiresMs>.<signature>`; the mod reads the
account id from its first part.

| Route                                              | Mod sends the cookie                              | Server requires a session             |
| -------------------------------------------------- | ------------------------------------------------- | ------------------------------------- |
| `POST /api/community/auth/register`, `.../login`   | no                                                | no                                    |
| `GET /api/community/auth/me`                       | yes (`CloudApi`), and `whoIs` once signed in      | no: 200 with `user: null` when signed out |
| `/api/library`, `/api/library/designs/**`          | yes, always (`CloudApi`)                          | yes: 401 without                      |
| `GET /api/accounts/search`                         | yes                                               | yes                                   |
| `GET /live/nearby`                                 | yes                                               | yes                                   |
| `GET /live/health`                                 | yes (`CloudApi`)                                  | no                                    |
| `POST`, `PUT`, `DELETE /api/community/plans[/id]`  | yes, once signed in                               | yes                                   |
| `GET /api/community/plans`                         | only for `mine=1`                                 | no (`mine=1` signed out lists nothing)|
| `POST /api/community/plans/{id}/download`          | no                                                | no                                    |
| `POST /api/feedback`                               | no                                                | no                                    |
| datasets                                           | no                                                | no                                    |
| `WS /live`                                         | yes, on the upgrade, and no `Origin`              | a bad or missing session is accepted, told `auth` and closed 4001 |

## A guarded site: one extra header on every request

A site behind a guarding reverse proxy lets a request through only when it carries a pre-shared header, and answers
every other one with a 302 to its sign-in page, which the mod cannot follow. The install's operator gives the mod that
header in the `network` category of `config/gtnhplanner.cfg`:

```
network {
    S:headerName=X-Access-Key
    S:headerValue=<the value the site's owner gives out>
}
```

- Keys `headerName` and `headerValue`; the system properties `-Dgtnhplanner.header.name` and
  `-Dgtnhplanner.header.value` win over the file, as for the other keys. Both empty is the default and sends nothing;
  one without the other sends nothing and warns with the key names.
- A name is refused (nothing is sent, a warning names the key) when it is not an HTTP token or is one the client sets
  itself or that frames a request: `Cookie`, `Host`, `Content-Length`, `Content-Type`, `Transfer-Encoding`, `Upgrade`,
  `Connection`, `User-Agent`, `Origin`, `Accept`, `Accept-Encoding`, `Expect`, `TE`, `Trailer`, `Keep-Alive`,
  `Content-Encoding`, `Set-Cookie`, and any `Sec-WebSocket-*` or `Proxy-*`. A value is refused when it holds anything
  but printable ASCII (no line break, no control character).
- The header goes on every REST request (`CommunityApi.send`, `CloudApi.send`) and on the `/live` WebSocket upgrade
  (`LiveSocket.connect`), only when the request's host and port are the configured site's or the live service's. Any
  other host gets none, and the update check sends none even to the site's own host.
- A redirect is never followed, so the header cannot reach another host that way. A 301, 302, 303, 307 or 308 from the
  site is reported as a refusal (status kept) whose text is `SiteHeader.SIGN_IN`, "The site asked for a sign-in the mod
  cannot do. If its owner gave you an access header, set it in the mod's config.", never a parse error. The cloud
  session shows that text once as a notice and stops retrying the live socket (state `CLOSED`, reason `sign-in`).
- The value is never logged, never in an exception or a refusal text, a crash report or the debug overlay. There is no
  getter for it; the one place that holds it is `library/SiteHeader`.
- The dev stand-in plays such a site: `MOCK_REQUIRE_HEADER=<name>=<value> node tools/dev/mock-library.mjs` answers 302
  to `/sign-in` on every route, and on the WebSocket upgrade, without exactly that header, and serves as usual with it.
  Its log says only that the header was missing.

Pinned by `SiteHeaderConfigTest` (keys, refused names and values, which hosts get it), `SiteHeaderTest` (every request
kind and the upgrade carry it, a 302 gives the plain text and is not followed, no value in any log line or exception),
the `CloudSessionTest` sign-in case, and `src/test-support/wire-mock-header.test.ts` (the node stand-in behaves the same).
