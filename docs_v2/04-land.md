# 04 — Land

## Two levels: territory and domain

| | Territory | Domain |
|---|---|---|
| Belongs to | One **village** | The **country**: shared by all its villages |
| Shape | Irregular. A noisy blob around the bell that follows what has been built. | A set of chunks (see below) |
| Size (proposed defaults) | Starts at radius ≈ 64, grows with construction, maximum radius ≈ 160 (configurable as an area). | At generation: the village's share of the world (see below), capped at radius ≈ 512 from its bell |
| Villagers build here | Houses with their workplaces, walls, roads, lamp posts; terraforming; demolishing illegal builds. | Only outposts, and new villages of the same country |
| Villagers defend against | Everyone: every monster or hostile mob is killed; laws apply to the player. | Only other countries |
| Player may | Only what the law allows ([05](05-law.md)); anything on their own plot. | Anything |

- **Noise.** The territory shape noise is deterministic from the village seed, so borders stay
  stable across reloads.
- **Implementation.** The border in each direction lies at `radius × factor(angle)`, with the
  factor a smooth periodic noise in [0.75, 1] (config `land.territory_noise`). The radius is the
  outer bound of the shape. A generated village starts with a territory that holds all its pieces,
  even past the maximum. Construction grows the radius up to the maximum area
  (`land.territory_max_area`, 80 425 ≈ π·160²).
- **Storage.** Domain ownership is stored **per chunk** (chunk → country). This makes borders
  cheap to look up and easy to redraw when land is bought or conquered. The generation split
  (below) is deterministic, so it is computed on demand and cached; only chunks whose owner
  differs from it (bought, conquered, claimed by a founded village) are saved.

### Domains at world generation

Generated villages **split the land between them**. Their domains touch but never overlap.

- **Voronoi split.** Each chunk goes to the nearest generated village, with a little noise on the
  borders so they do not look ruler-straight. A village is located by its structure start chunk
  (its "seat"), not its bell: the seat is known before the village generates. A generated village
  that has not registered yet is a country whose id is derived from its seat; on registration the
  village takes that id as its country.
- **Cap.** A chunk farther than the cap (≈ 512) from every bell belongs to no one.
- **Laziness.** Chunks are assigned when first needed (a bell is registered, or a chunk is
  looked up). The nearest bell is found among registered villages and structure starts from
  vanilla's village placement, so no chunk assignment changes later when another village loads.

Reuses from the `legacy` port: territory radius growth from structures. The fixed radius becomes the noisy
shape, and a cap is added.

### Redrawing borders

After generation, domains change only by agreement or force:
- **Buying land.** One country sells border chunks to another through a treaty, paid in
  emeralds or debt notes ([06](06-politics.md)).
- **Conquest.** A conquered village brings its share of the domain to the winner. Peace
  treaties can also transfer border chunks.
- **New villages.**
  - A new village founded on unowned land claims chunks around its bell, up to the cap, without
    taking anyone's chunks.
  - A new village inside the own country's domain claims nothing new.

## Plots and houses for the player

- **What the elder sells:**
  - **Plots.** Land inside the territory, 17×17 by default (more sizes *(proposed)*), not on
    existing buildings, roads or fields.
  - **Empty houses.** Houses with no owner: the owner died, the village was conquered, or the
    house is left over from worldgen.
- **Deed map.** The player receives a vanilla filled map centred on the property, with red X
  decorations outlining it, like a treasure map. Ownership lives on the server. Losing the map
  loses nothing.
  - A plot is bought as a land deed (paper). Used on the ground inside the territory, it marks the
    plot centred there and turns into the deed map.
  - An empty house is sold as its deed map directly; the elder offers the two nearest to the bell.
    A house is empty when it has beds and nobody of the village sleeps, lives or stores anything in
    it, and no birth is waiting for it. The sold house becomes a plot covering it plus one block.
  - **A house on the player's plot.** The elder also offers to build a house on a plot the player
    owns in the village: the largest village house of the biome that fits the plot on clear ground,
    entrance toward the bell. The price is 4 emeralds plus one per
    `land.house_order_blocks_per_emerald` (6) blocks of the house, paid to the treasury. The player
    gets a written contract, and the masons build the house on the plot as public work; it is the
    player's. Ordering is never required: the player may build on the plot freely instead.
- **Rights on a plot.** The player builds and breaks freely. Villagers do not interfere: no
  repairs, no terraforming, no lamp posts, no roads through it.
- **The plot stays part of the village.** The walls enclose it, and warriors defend it against
  monsters, other countries and night agents like any other part of the territory.
- **Money.** The price goes to the treasury.
- **Illegal builds.** Player blocks in the territory **outside** the player's plots are illegal.
  After the warning ([05](05-law.md)) they are demolished to the ground (see Terraforming). The
  removed blocks go to the treasury as confiscated goods. Buying a plot that covers the build
  before the miner arrives makes it legal.

Reuses from the `legacy` port: deed flows. Deeds
become maps instead of paper.

## Construction

Every building has an **owner**: a household, the village, or a player plot.

| Commissioner | What | Paid by |
|---|---|---|
| Parents | The child's house, with the workplace for the child's profession: a field, a pen or stable, workstations ([03 §Births](03-economy.md)) | the parents |
| Treasury | Walls, gates, outposts, the elder's house and warehouse, repairs | taxes |
| Treasury | Infrastructure: roads, tunnels, lamp posts, pit filling, demolishing illegal builds | taxes |

- **Builders.**
  - Masons build houses and walls.
  - Carpenters build lamp posts.
  - Miners terraform and demolish.
  - Farmers till the new field; herders fence the new pen.
- **Payment.** Each worker is paid per job at market rates. Materials are bought at the
  gathering and charged to the commissioner.
- **Budget.** The treasury keeps its public works (walls, lamp posts, terraforming, demolition)
  going only while it can pay for them: while it holds more emeralds than three days of army wages
  plus the pay it already owes. Workers are paid per unit of work (`labor_units_per_emerald`).
  Repairs are always done; houses are paid by the parents.
- **No speculative houses.** Empty houses come only from deaths, conquest and worldgen.
- **Reachable sites only.** A site is accepted only if a villager standing by the bell can walk
  to its door; otherwise the search moves on (implemented in phase 1).

Reuses the whole pipeline of the `legacy` port: site search, plans, the builder task, material
fetching, roads and repair, plus field and pen creation.
The triggers change: planning now starts from commissions, not from automatic expansion.

## Terraforming and demolition (miner)

The miner **flattens hills**, **digs tunnels**, **fills pits** and **demolishes illegal builds**
inside the territory.

- **Legal player buildings.** The only thing the miner never touches: everything on the
  player's own plots.
- **Illegal player builds.** Torn down to the ground, without the limits below.
- **Natural terrain.** Changed only for a reason, so the landscape is not bulldozed:
  - preparing a planned site, a road or a wall line;
  - filling pits within ~4 blocks of a road, house or field, where they are dangerous.
- **How much.** At most ~6 blocks of height change per column for a site. A road that would
  need more becomes a **tunnel** through the hill instead.
- **Materials.** Fill uses real blocks: the miner's own spoil, or dirt or cobblestone bought at
  the gathering.
- **Unreachable houses.** A house, bed or chest villagers cannot walk to (generated villages
  have some on hillsides) gets a path cut or built to it: steps, a ramp or a tunnel.
- **Implementation.**
  - *Demolition.* A player block in the territory outside the owner's property is condemned
    once the elder's notice was delivered to its owner and the grace period is over. Nothing is
    condemned while the village fights. The miner tears builds down from the top; drops and
    container contents go to the treasury.
  - *Pits.* A dry column whose ground lies 2–6 blocks below the ground two blocks away on both
    sides (along x or z), within 4 blocks of a house, road, field or bed, is filled up to that
    rim with dirt or cobblestone of the treasury.
  - *Sites.* Masons still prepare their own sites; site search accepts at most 4 blocks of
    height difference, inside the 6-block limit.
  - *Unreachable beds* (`Access`). Every 30 s the village checks one household bed: can a villager
    by the bell walk next to it? The walk is planned the vanilla way, in up to four legs (each
    from where the last ended, as a villager plans again on a long way). For a bed it cannot
    reach, a search (`Trail`) finds a way from where the walk ended to a doorway of the house or
    the bed's side that may dig and fill: two cells of head room through natural ground, leaves or
    a roof eave above entrance steps (house blocks cost more), one block of fill for a missing
    step. Land plots, player blocks, walls, beds and containers are never touched. The miner does
    the changes. A bed is checked again a day later.
  - *Road cuts and tunnels.* Where the road's route meets ground more than a block higher, it may
    go on through the hill: on the level (a tunnel) or one block up (a cut climbing to the top).
    The mason digs a passage two blocks high before laying the path; the earth goes to the
    treasury. Nothing built, no player block, house or plot, and no water nearby.
  - The miner's land work is public work within the treasury's budget, and comes before mining.

## Layered walls

- **First ring.** The first wall ring follows the territory border at the time it is built.
- **Growth.** When the territory grows past a ring, a **new ring is built outside**. The old ring
  is **kept**, maintained and repaired. Over time this gives defence in depth for sieges
  ([07](07-warfare.md)).
- **Gates.** Every ring has gates where roads cross it. Old rings keep their gates open in
  peacetime.
- **Player plots** are part of the village. A ring that would cross a plot goes around its
  **outer** side, so the plot stays inside and protected.
- **Funding.** Walls are military buildings, paid by the treasury.

- **Implementation.** A ring is laid out for the territory radius at the time: it follows the
  noisy border two blocks inside it, and is pushed out along the ray around player property. The
  village's pieces, houses, fields, storages and pen grow the territory (up to its maximum) so
  the ring encloses them. Once the territory radius is 16 blocks past the current ring, a new ring
  is planned and the old one is kept. Missing blocks of every ring are put back. Gates stand where
  the ring crosses a road and on the bell's axes. Config `land.layered_walls` off: the ring moves
  out instead, as before.
- **Houses or walls first** (`BuildPriority`). Every morning the elder decides what the masons
  build first. Danger puts the walls first: a war, hostile neighbours, a war on a player, or a
  raid (not the monsters of every night). Otherwise a house paid for (by parents or a player)
  comes first, and walls go up when no house waits. Walls that did not grow for a day give way to
  a paid house. A lone mason follows the choice; with several, one builds walls and the others
  houses. Among the houses, a mason takes a paid one before the village's own.

Reuses from the `legacy` port: ring layout, materials and gate control. Change: rings are never
demolished or moved; new rings are added.

## Lamp posts (carpenter)

Instead of loose torches, the carpenter builds **lamp posts**: a 2–3 fence column with a
torch, or a lantern once the smith sells them. They go along roads at regular spacing, on
squares, at gates and wherever the darkness scan of the `legacy` port finds dark spots. Paid by the treasury as
infrastructure.

Implementation: posts are planned beside road blocks, on the square around the bell and inside
the wall gates, where the block light is below 6 (no light within about 10 blocks). A post is two
oak fences with a lantern (if the treasury has one) or a torch, from the treasury's fences, planks
or one log, and torches or coal. The carpenter builds them before felling trees or working wood, but not while a house is under
construction, and never from the logs the treasury keeps for houses (48).
Dark spots it passes are still lit with a short post, as in phase 1.

## Expansion

1. **Trigger.**
   - The territory reaches its maximum area, and
   - a candidate pair cannot get a plot, or adults lack houses.
2. **Search party.** About 5 villagers by default. It always includes:
   - a future elder (the wealthiest or best-reputed volunteer);
   - at least one warrior;
   - at least one farmer.

   Members take their own money and goods. The treasury adds a starting grant.
3. **Site choice.** The new territory must not overlap any existing territory and needs room to
   grow. In order of preference:
   1. **Inside the own country's domain.**
   2. **On unowned land**, adjacent to the own domain if possible. The new village claims chunks
      around itself.
   3. **Inside a foreign domain.** The country negotiates a border change: buy the land with
      emeralds or debt notes, or take it by war. The owner may refuse to sell even if the price
      can be paid ([06](06-politics.md)).
   4. **Nothing reachable or agreed.** The party waits and the village stops growing. That pressure
      pushes the country toward a war over territory.
4. **Founding.** The party places a bell (and the first storage, as in the old colonization) and
   becomes a new village of the **same country**.

Implementation notes:
- **Party.** Up to five settlers, at least three. The future elder is the settler with the most
  emeralds at home. Then a warrior and a farmer join if their trade can spare one, then adults
  without a bed. Every trade keeps at least one member at home, and the elder stays. Each settler
  takes the emeralds from its home chests. In the new village they are paid out to its new
  household; if the party comes back, they are paid back at home. The treasury adds
  `land.expansion_grant` emeralds (12) plus building materials and food.
- **New village.** The party always has a warrior and a farmer. A settler takes up farming as
  first of its line if no farmer came along. No starting capital is created for the new
  village.
- **Site.** Loaded land 288–448 blocks away. The largest territories of the new and of every
  existing village must not overlap. Inside a foreign domain the country offers to buy the land
  ([06](06-politics.md)).
- **Travel.** A settler unloaded for a minute stays home. A settler that cannot catch up with the
  leader for 30 s climbs out to it (teleport).
- **A village nobody watches** (`AbstractExpedition`). A crowded abstract village (territory at
  its maximum, and an adult without a bed or a birth house finished as a record) sends its party
  as a record. Settlers come from the households; the site is searched along the same pattern, a
  few candidates per pass: unloaded land is judged by the terrain generator (dry, flat within
  3 blocks) and by the records (territories, cores, domains, player builds and plots). The party
  travels about 150 blocks an hour. On arrival the site is held loaded for a moment
  (`world.SiteLoader`): the nearest suitable spot is found with real blocks, the cargo pays the
  camp, and the settlers appear there (from their stored data, or as new villagers that take over
  the households of members that never left their unloaded home chunks; the originals are dropped
  when they load). The live expedition code then founds the village while the site stays loaded.
  A party that finds no site, or gives up at the site, goes home as a record.

Reuses from the `legacy` port: party travel, path recovery and founding. Changes: the trigger, the party
members, the site rule, and travel that is abstract when unloaded.
