## New

- A website plan opens with every card on the board. Cards the game cannot run (switched off on the website, ore veins, small ores, underground fluids, Thaumcraft and essentia recipes, recipes this pack lacks, cards with no recipe in the plan) now show at their place with the plan's name, ports and counts, and their wires and drawers. Before, they were left off the board.
- Those cards say VIEW ONLY on their foot, with the reason. They can be moved, wired and deleted. Their settings, count and recipe can only be changed on the website.
- A view-only card counts in the solve when the website's numbers cover its recipe (ore veins, underground fluids, a recipe this pack lacks), at the plan's count. A switched-off card, a card with no recipe, and bee or crop cards are shown outside the solve and their wires carry nothing.

## Better

- Exporting, posting or syncing such a plan writes those cards back exactly as the website made them. A view-only card moved on the board goes out at its new place; one deleted is gone, with its recipe and wires.
- Saves from the previous build kept these cards out of sight; they now show when the plan is opened.
- The import report lists these cards as shown, not as left out.

## Fixed

- A website drawer that is both fed and drawn from kept no drain setting (product, byproduct or trash) when the plan went back out. It now keeps it.
- A cloud plan lost the website's setting pins for machines the board has no card of when a later save changed the board's pins. They now stay.
- A server that sends the game to a sign-in page now gets a plain message ("The site asked for a sign-in the mod cannot do") in the library and for cloud plans, instead of a read error.

## For the server operator

Two new keys in the `network` category of `config/gtnhplanner.cfg`, both empty in a fresh install:

```
network {
    S:headerName=X-Access-Key
    S:headerValue=<the value the site's owner gives out>
}
```

- For a site behind a guarding reverse proxy that lets a request through only with a pre-shared header. The mod cannot
  follow the proxy's sign-in redirect. With both keys set, the header is sent on every request to `siteUrl` and to the
  live service (the library, accounts, posting, cloud plans, the `/live` WebSocket upgrade), and to no other host.
- Both empty (the default) sends nothing. One without the other, a name that is not a header token, a name the client
  sets itself (`Cookie`, `Host`, `Content-Length`, `Content-Type`, `Transfer-Encoding`, `Upgrade`, `User-Agent`,
  `Origin`, any `Sec-WebSocket-*` or `Proxy-*`, and the rest of that list in `conformance/wire/README.md`) or a value
  with a control or non-ASCII character turns it off, with a warning in the log naming the key and never the value.
- `-Dgtnhplanner.header.name` and `-Dgtnhplanner.header.value` win over the file, like the other keys.
- The value is never written to a log, an exception, a crash report or the debug overlay. The config file holds it in
  plain text, so keep its permissions as for any secret.
- The mod never follows a redirect. When the site answers one (the header missing or wrong), the player is told once that
  the site asked for a sign-in the mod cannot do, and cloud plans stay on this computer until it is fixed.
- The update check (`updateUrl`) sends no header.
