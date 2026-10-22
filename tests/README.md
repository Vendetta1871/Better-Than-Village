# Server tests

Headless scenario tests: `servertest.py` starts a dedicated server for a checkout (seed
`btv-test`), sends the commands of a scenario over RCON and prints the responses. The checks are
read from the output and the server log (`run/logs/latest.log`, `[war]`, `[night]` and other
tagged lines); there are no automatic assertions.

```sh
BTV_JAVA_HOME=/path/to/jdk21 python3 -I tests/servertest.py <project-dir> tests/<scenario>.txt --port 25611
```

- `--port` is the RCON port; the game port is 100 lower. Use a different port per parallel run.
- `--keep-world` keeps `run/world` instead of starting from a fresh world.
- `BTV_PROXY=host:port` sends Gradle through an HTTP proxy.

## Scenario syntax

One command per line, without the slash. Besides commands:

- `// text` — a comment.
- `#sleep N` — wait N seconds.
- `#wait-log REGEX [timeout]` — wait for a server log line.
- `{LX+16}`, `{LZ}` — arithmetic on the coordinates captured from the last response that
  contained `[x, ~, z]` (for example `locate structure #minecraft:village`).

With seed `btv-test` the nearest village is at about (208, 192) and the next one at about
(766, 388). Forceload at least ±96 blocks around a village, or villagers outside the loaded area
freeze. `/btv status`, `/btv villagers` and `/btv land` dump the colony at a position
(`execute positioned {LX} 100 {LZ} run btv status`).

## Scenarios

| Scenario | Checks |
|---|---|
| `smoke.txt` | The village registers and ticks |
| `professions.txt` | Fixed professions, conversion and succession |
| `households.txt` | Households, beds, home chests and starting capital |
| `stock.txt` | Work tasks take from and deliver to the household stock |
| `market.txt` | Gatherings, prices, player offers, taxes and wages |
| `food.txt` | Farming, fishing and meals |
| `gather.txt` | Felling, mining and deliveries |
| `tenday.txt` | Ten days of a village: growth, births, construction (the regression baseline) |
| `land.txt` | Territory, plots, illegal builds and their demolition |
| `walls.txt` | Layered walls as the territory grows |
| `lamps.txt` | Lamp posts along roads and on dark spots |
| `expand.txt` | An overcrowded village sends a search party and founds a daughter village |
| `find.txt` | Survey of the villages around the test village |
| `law.txt` | Offences, warnings, war on a player, surrender and peace (with a fake player) |
| `world.txt` | Two countries, the abstract world, caravans, orders and diplomacy |
| `war.txt` | A country conquers a neighbour with no player near |
| `siege.txt` | A live siege of a two-ring village |
| `player.txt` | War on the player: the base, the offensive, the storm, a coalition, night operations |
| `playeraway.txt` | A campaign raids the player's base while the player is far away |
| `nightfire.txt` | Arson by an abstract village burns real blocks once the target loads |
| `cavalry.txt` | Riders and the village golem join a campaign |
| `credit.txt` | A player buys on credit, repays, and defaults into war |
| `houseorder.txt` | A player buys a plot and orders a house on it; the masons build it |
| `access.txt` | The miner clears the way to a house the villagers cannot walk into |
| `cure.txt` | A villager calls the healer, who cures zombie villagers day and night; with no cure the warriors fight |
| `expedition.txt` | A search party travels as a record and founds a village |
| `growth.txt` | Births and conscription of an unloaded village |
