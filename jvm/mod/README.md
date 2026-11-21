<p align="center">
  <img alt="GTNH Planner" src="src/main/resources/gtnhplannerlogo.png" width="128">
</p>

# GTNH Planner

Plan GT New Horizons factories in game. GTNH Planner is the Minecraft side of the GTNH Factory Flow planner in this
repository: a flowchart planner that lives in NEI, builds its cards from NEI's own recipes, and balances itself.

## What it does

- **Cards from NEI.** Look an item up with R or U, then press + (or the plan button beside it) to put a recipe on
  the board. Pick the machine; GregTech tiers, amps, coils and overclocks are worked out for you.
- **A board that solves itself.** Set a rate on what you want, or pin a machine count, and every card's machine
  count and every wire's flow follow. Wires route themselves and hop where they cross.
- **Your plans, and everyone's.** Tabs are your open plans; every plan you make is kept in the Library's My plans.
  When the pack's config names a website, the Library also holds its public setups: search them, open one as a plan,
  or right-click something on the overview to see the setups that make it.
- **The same account as the website.** With a website set up, sign in with its account to post a plan to the public
  library, where it shows on the website and in game, and to keep cloud plans and edit them live with others.
- **Works with no website.** The jar names no host: the website, live service, update check and help page are empty
  until a pack sets them in `config/gtnhplanner.cfg` (`network` section), and until then the game contacts nothing.
  Plans still share as a plan code, in chat, as a file or as a picture.
- **Client side only.** No server mod needed.
  
## Images
<img width="1922" height="1081" alt="image" src="https://github.com/user-attachments/assets/5273a93c-d1f2-4dbd-9137-7a8e348d163b" />

<img width="1911" height="1075" alt="image" src="https://github.com/user-attachments/assets/236653f5-76b8-44bf-b0fc-6796f145d6fd" />

<img width="565" height="309" alt="image" src="https://github.com/user-attachments/assets/d9671f45-de26-448b-b750-b00a163fe34d" />


## Dependencies

- Minecraft **1.7.10** with Forge, in the GT New Horizons pack (RC2)
- **Not Enough Items** (GTNH's), **ModularUI2**, **GTNHLib**, **Mixin**

## Building

The mod is the `mod` project of the Gradle build in `jvm/`, beside the core modules it shares with the website. From
`jvm/`:

```bash
./gradlew :mod:build
```

The jar lands in `jvm/mod/build/libs/`. A release jar is built at its `mod-<version>` tag with
`tools/ci/mod.sh --release mod-<version>` from the repository root. See `AGENTS.md` for the toolchain and the full
build and test commands.

## Credits

GTNH Planner started as a fork of [PlanNH](https://github.com/sbancuz/PlanNH) by **Sbancuz**, with UI code by
**[TheYoingLad](https://github.com/TheYoingLad)**. It has since been rebuilt and renamed, so the two never collide.

## Feedback

Bugs and ideas go through the planner's Feedback key in the top bar. It sends a report to the website the pack's config
names, or says sending is not set up when none is; a pack can also set a help page there (`supportUrl`).

## License

MIT — see [LICENSE](LICENSE).
