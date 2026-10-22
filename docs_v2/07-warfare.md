# 07 — Warfare

## Warriors

- **Pay and gear.** Warriors are paid by the treasury. Gear (weapons, armor, shields, horses,
  potions) is treasury property, issued to warriors and returned on death or when they change
  class.
- **Peacetime duties:**
  - patrol the territory and guard the gates;
  - kill every monster or hostile mob in the territory;
  - answer alarms;
  - fight vanilla raids.

### Shifts

Warriors serve in **two shifts**:

| Shift | On duty | Sleeps |
|---|---|---|
| Day duty | Day: patrols, gates, escorting caravans | At night, in their own bed |
| Night watch | Night: walls, gates, rounds through the streets, watching sleeping houses | During the day |

- **Split.** By default 50/50, configurable. In war, the night watch is reinforced.
- **Risk.** The sleeping day shift is the main target of enemy saboteurs (see Night operations).
- **Alarms.** Raise both shifts: sleepers wake up and arm.

Reuses from the `legacy` port: combat, kiting, line-of-fire checks, shields, eating and
retreating, patrols and gear rendering, plus the threat and alarm manager.

### Classes

| Class | Weapons | Role |
|---|---|---|
| Ranged | bow or crossbow | Walls and towers in defence; covering fire in a siege. |
| Infantry | sword (+ shield if available) or axe | Holds gates and breaches, assaults, guards outposts. |
| Cavalry | sword, on a horse | Field battles, pursuit, intercepting raids on outposts. |

- **Switching.** A warrior switches class whenever the treasury has the gear (and a horse for
  cavalry).
- **Target mix.** The army aims for a class mix set by the situation:
  - **Defending walls:** more ranged.
  - **Assault:** more infantry.
  - **Field campaign or pursuit:** more cavalry.
- **Horses** come from the herder's stables. Mounted pathfinding is the riskiest technical part.
  `warfare.cavalry` can switch it off, and cavalry then fights as infantry.

## Iron golems

- **Built, never summoned.** Vanilla golem summoning by villagers is off
  (`warfare.natural_golems`). A village builds its golem from 36 iron and a pumpkin of its
  treasury, so a golem is a costly unit. Farmers start with pumpkin seeds and grow pumpkins for it.
  A golem generated with the village counts as built.
- **Limit.** At most **one village golem** per village.
- **No regeneration.** A golem heals only when a warrior repairs it with an iron ingot of the
  treasury (25 health per ingot, as a player does).
- **Use.** The village golem guards the territory and marches with its village's campaigns.
- **Player golems** do not count toward the limit. The village never commands them: they are
  never taken on campaigns or sent against the player.

Reuses golem management from the `legacy` port,
with the cap added.

## Outposts

An outpost is a small fortified camp: a palisade or low wall, a watchtower, a chest and beds.
It is built from vanilla blocks to a code-generated plan.
- **Own domain.** Built for border watch on the side facing a hostile neighbour or a player's
  base.
- **Enemy domain.** **Mandatory before every attack.** It is the staging point: supplies,
  regrouping, rest. An attack cannot start until its outpost is finished.
- **Destroying one.** The campaign stalls until it is rebuilt. This is the defender's main
  counter-play.
- **Funding.** Treasury (military construction); built by the campaign's masons or warriors.

## Campaigns

1. **Decision.** The ruler declares war ([06](06-politics.md)) or answers a call from an ally.
2. **Mobilization.** Warriors of the chosen villages (and their golems) gather at the capital or
   at a border outpost. A share always stays home as a garrison (configurable).
3. **March.** The army travels to the enemy domain.
4. **Outpost.** It is built in the enemy domain near the target.
5. **Siege or battle.** See below.
6. **End.** One of:
   - all enemy warriors are destroyed, leading to conquest ([06](06-politics.md));
   - a peace treaty is signed;
   - the attackers break off because the outpost was lost repeatedly.

### War weariness

A side does not have to lose its whole army to ask for peace. Each country tracks **war
weariness**. It grows with:
- the share of its warriors lost (the main factor);
- treasury spent on the war, and debt taken for it;
- war duration;
- villages lost or besieged;
- unrest (loyalty falling in its villages).

When weariness passes the elders' **tolerance**, the country asks for peace. Tolerance is
personal: it comes from the ruling elder's greed and the country's recent history. Some elders
give up after a few losses, others fight on. The stronger side may still refuse peace if it is
winning.

Steps 3–6 run **live** when a player is near and **abstract** otherwise
([08](08-simulation.md)).

## Sieges

The attackers behave like siege mobs in "Epic Siege"-style mods:
- **Breaching.** Infantry break wall and gate blocks, which takes time and tools.
- **Tunnelling.** They dig under a wall ring where it stands on diggable ground.
- **Climbing.** They pillar up or place ladders or scaffolding against walls.

The defenders react:
- archers shoot from the walls;
- masons repair breaches during the siege, paid by the treasury;
- healers support with potions.

Layered walls ([04](04-land.md)) mean every ring is a separate siege stage.

## Night operations

Besides open war, villages send agents at night against **neighbouring villages of other
countries** and against **the player**.

| Operation | Goal | Targets in priority order |
|---|---|---|
| Theft raid | Empty a chest | Treasury, the richest household chests, the player's chests |
| Sabotage: arson | Set fire to a building | Wooden houses, the elder's house, walls with wooden parts, the player's house |
| Sabotage: killing | Kill a villager | **Sleeping warriors** (day shift) first, then the elder, then other sleeping adults; a sleeping player |

### Who goes

- **Choice of agent.** The elder sends either a **warrior** or the **poorest villager**.
- **What the poor villager gets.** A share of the loot. It is a way out of poverty, an
  alternative to conscription.
- **Kit.** Flint and steel for arson, a weapon for killing; bought by the treasury.

### When

- **Only at night.** The agent leaves at dusk and must be back, or gone, by dawn.
- **Which operation is allowed:**

  | Against | Theft raid | Arson, killing |
  |---|---|---|
  | Country at peace, bad relations | yes | only as **revenge** for a caught thief |
  | Country in hostility ([06](06-politics.md)) | yes | yes |
  | Country at war | yes | yes |
  | Player, bad gossip | yes | only as revenge |
  | Player, terrible gossip or at war | yes | yes |

### Getting caught

- **Detection.** The night watch, or any awake villager with line of sight, can spot the agent.
  A spotted agent is attacked.
- **Agent caught or killed.** It is an **incident**, not an automatic war:
  - relations with the sending country worsen;
  - the victim may answer with **revenge**: its own arson or killing, which is allowed even in
    peacetime;
  - incidents add up. Enough of them push the relations into **hostility**, and repeated
    incidents become a cause of war ([06](06-politics.md)).
- **The player catches an agent.** The player may kill them freely. It is not an offence against
  the agent's country, because the agent was acting against the player.
- **Abstract mode.** With no player near, the outcome is rolled from the agent's skill against
  the strength of the target's night watch. Stolen goods and fires are written to the ledgers
  ([08](08-simulation.md)).

## Abstract battles

When no player is near, a battle is resolved by a strength model:
- **Strength of a side** = Σ warrior power (class, gear tier, health), with modifiers for the
  number of intact wall rings, the golem, and outposts.
- **Attrition.** Rounds of random attrition proportional to the enemy's strength, until one
  side breaks.
- **Result.** Casualties and wall damage are written to the ledgers. They are applied to the
  real entities and blocks when the chunks load ([08](08-simulation.md)).
- **A player arrives mid-battle.** The surviving forces materialize and the battle continues
  live.

## War against the player

### Player strength score

The score is used to price peace and to decide who joins. It is built from vanilla statistics
plus our own counters:
- diamonds mined (vanilla `mined` statistic);
- mobs killed;
- villagers and warriors killed, and losses inflicted in past battles with this country;
- iron golems the player built (our counter);
- the player's current gear tier.

### Finding the player's base

Other mods mostly treat "home" blocks as the signal: beds, chests, doors, torches. Examples:
- **Mob Attacks Your Base** makes beds, chests, doors and torches attract monsters.
- **Base Raid** and **Survival Plus** start their attacks at the player's bed or spawn point.

We do the same, with data we already have:

1. **Candidates.** Clusters (e.g. 32×32 areas) of **player-placed home blocks** from the
   player-block registry:
   - beds; the respawn bed counts most;
   - chests, barrels and shulker boxes;
   - furnaces and crafting tables;
   - doors.
2. **Score.** Each cluster gets a weighted block count plus **presence**: how often the player
   was there at night or logged out there. Presence is sampled cheaply, e.g. every few minutes.
3. **The base** is the top cluster. Outside our registry the fallback is the player's respawn
   point. A player with no base found is only hunted inside the country's domain.
4. **Uses.** The base is the target of offensives and of night operations against the player.

### Behaviour

- **Offensive.** The country builds an **outpost** near the base and storms it with the same
  siege behaviour as against a village.
- **Night operations** target the base's chests, the player's house and the player asleep.
- **Coalitions.** Other countries join based on the strength score, their relations with the
  attacking country, and their own gossip about the player. A strong player can face a
  coalition.
- **Ending the war.** Surrender and peace treaties: [05](05-law.md). War weariness applies here
  too: losing a significant part of the army to the player is enough for the country to ask for
  peace.

## Vanilla raids

Unchanged. The village defends itself with warriors, the golem and its walls. Raids do not
count as war with any country.

## Implementation notes (phase 5)

The `warfare` package. Settings are in the `warfare` section of the config.

- **Campaigns** (`Campaign`, `Campaigns`).
  - **Launch.** Declaring a war launches the aggressor's campaign against the defender's village
    that is nearest its capital and weakest. Every day, a side at war with no army in the field
    sends one: the aggressor at four fifths of the enemy's strength, the defender only when
    stronger (a counter-attack).
  - **Mobilization.** Every village that obeyed the call to arms sends its warriors beyond the
    garrison (`warfare.garrison_share`, 0.34, at least one stays). The staging village's golem
    joins: from a loaded village as itself, from an abstract one as a record that walks as a
    stand-in with its id when the army comes into a loaded area. Warriors of other villages reach
    the staging village after the walk there. A campaign needs `warfare.min_army` (2) soldiers.
  - **Cavalry.** A cavalry soldier takes a horse from its village's stables (the stables count one
    less until it is back); without a horse it marches as infantry. The horse is stored and
    materialized with its rider, and a fresh saddled horse appears for a rider whose horse was
    never in the world. A rider steers: the horse follows the rider's path. A dead horse leaves
    its rider on foot. Back home the horse returns to the stables.
  - **Phases.** Mobilize (`warfare.mobilize_ticks`, 2000), march, outpost, siege, return, home.
  - **March.** Abstractly the army moves `warfare.army_blocks_per_hour` (150) blocks an in-game
    hour. It stops `warfare.outpost_distance` (24) blocks outside the target's outermost ring, on
    the side it comes from.
  - **Soldiers in the world.** As for caravan leaders, a soldier that leaves entity-ticking chunks
    is stored in the campaign (`world.Materializer`, shared with caravans). Stored soldiers, or
    stand-ins for those whose entity never left an unloaded home, are spawned where the army
    stands in entity-ticking chunks. A live soldier leads the march and the others follow it. A
    soldier's original entity that loads while it is away is kept out.
  - **Home.** Back at the staging village, its own soldiers are released. The others are stored
    and spawned at their villages once those are live. Stand-ins are dropped.
- **Outposts** (`Outposts`). A 9×9 fence palisade with an opening toward the target, a log
  watchtower with a torch, a chest and a bed.
  - **Labour.** 10 per soldier and hour; the outpost needs `warfare.outpost_work` (60). It is
    paid by the staging village's treasury (logs and planks). Near a player its blocks go up as
    the labour progresses.
  - **Losing it.** The siege starts only when the outpost is built. Abstractly the defenders may
    sally when their field power is at least half the army's; a won sally destroys the outpost.
    Live, a broken outpost chest destroys it. The campaign then rebuilds it, and breaks off after
    `warfare.outpost_max_losses` (3) losses.
- **Abstract battles** (`Battle`).
  - **Power.** A warrior is `warfare.warrior_power` (2) plus its gear tier (0..3 by weapon, up to 1
    by armor and shield) plus a class bonus, times its health. In a siege infantry gets +0.5 and
    archers +0.25; in the field cavalry gets +1; defending archers get +0.5. A golem is
    `warfare.golem_power` (10: live, one golem holds off about five armed villagers).
  - **Walls.** Each intact built ring multiplies the defence by 1 + `warfare.ring_bonus` (0.35). A
    ring counts once a third of its columns are built.
  - **A round** (every in-game hour of the siege): the army first wears down the outermost intact
    ring. A breached ring loses three wall columns facing the army; they come down when the
    village loads, and its masons rebuild them. Then each side deals 0.3 times its power, with
    randomness, in one-point hits spread over the enemy's units. The defenders' share is divided
    by the wall bonus. Healers heal the most wounded defender each round, more with a potion from
    the treasury.
  - **End.** The village falls when no warrior and no golem is left. The army breaks off below
    30% of its initial power.
  - **Deaths.** A loaded entity is killed. Otherwise the villager leaves the village's records at
    once and its entity is dropped when its chunk loads (`Casualties`, `ColonySavedData.fallen`).
- **Live sieges** (`Siege`, `SoldierTask`). Soldiers fight the target's warriors and golem, and
  players at war with their country or hitting them.
  - **Rings.** Before each ring a soldier works on a breach near it: a wall gate within 16 blocks
    is broken first. Otherwise, by turns, a soldier breaks the ring's blocks of a column and its
    neighbours at feet and head height, digs a tunnel two blocks under the wall where the ground
    is diggable, or builds a coarse dirt staircase to the top of the wall and drops inside.
  - **Jumping.** A rider whose horse clears the wall (the vanilla jump curve of the horse's jump
    strength, half a block of headroom) jumps it from a run-up outside, if the wall is at most two
    blocks thick and the ground inside is free. No block is broken.
  - **Getting stuck.** A soldier that makes no progress for 15 s tries another tactic. Without
    mob griefing it climbs over.
  - **Gates.** Wall gates never open for, nor path through, enemy soldiers.
  - **The fall.** The village falls when none of its warriors stands in its territory, its golem
    is dead and a soldier is within 10 blocks of the bell.
  - **Defence.** The village's warriors see enemy soldiers inside the territory as attackers
    (`ThreatManager.enemySoldier`), and masons rebuild broken wall blocks.
- **Conquest** (`Conquest`).
  - The village joins the winner with `politics.conquered_loyalty` (20).
  - Its treasury's emeralds go to the winner's capital. The debt notes it held now pay the
    capital, and a note the winner owed it is torn up.
  - The chunks of the loser's domain nearest to it go to the winner.
  - The old elder becomes a librarian. The adult with the best gossip (abstractly, the oldest
    household) becomes the elder.
  - A country with no village left is gone.
- **War weariness** (`Weariness`, accounts in `Relation.Side`).
  - **Weariness** = 60 × the share of warriors lost + 2 per day + 25 per village lost + 0.5 per
    hour besieged + a quarter of the emeralds spent + 5 per disloyal village.
  - **Tolerance** = `warfare.weariness_tolerance` (50) plus 100 times the ruling elder's greed
    (±30).
  - **Asking for peace.** Past its tolerance a side asks for peace. The winner refuses while its
    own weariness is under half its tolerance, it is 1.5 times stronger and its army besieges the
    asker.
  - **Terms.** A tribute of a quarter of the asker's capital emeralds (at most 48), the disputed
    border chunks, and the winner's debts to the asker written off. Loyalty drops by 5 in the
    loser's villages and rises by 3 in the winner's. A war still ends after
    `politics.war_max_days` (now 30).
- **Rebellion.** When the ruler's patience runs out and the rest of the country is stronger than
  the rebel village, the rebel secedes and the ruler declares war on it (cause `rebellion`).
  Conquered, it rejoins with low loyalty; otherwise it stays free. A weaker ruler tolerates.
- **Warriors** (`Army`).
  - **Classes.** Assigned at every census (and daily while abstract) by the situation. Besieged
    or defending: half archers. On campaign: half infantry and a quarter each of archers and
    cavalry. Otherwise an even mix. Archers are limited by the treasury's bows (and string), and
    cavalry by the stables' horses. Warriors keep their class while it fits.
  - **Gear by class.** A warrior that is not an archer returns its bow to the treasury, and only
    archers shoot.
  - **Shifts.** `warfare.night_watch_share` (0.5, two thirds in war) of the warriors serve at night
    and sleep by day. The patrol is led by an awake warrior.
  - **Horses.** A village with a herder breeds a horse every other day while it has fewer than a
    third of its warriors in horses (10 wheat each). Live villages keep them, tamed and saddled,
    at the pen. Cavalry mounts a village horse to fight in its own village and dismounts after;
    on campaign it keeps its horse (see Campaigns). `warfare.cavalry` switches it off; the debug
    command `/btv war horses <n>` sets the stables.
  - **Golem.** At most `warfare.golem_cap` (1) village golem, built from iron and a pumpkin;
    vanilla summoning is off (`warfare.natural_golems`). It does not regenerate; warriors repair it
    with iron. The census remembers its id for the strength model.
- **Night operations** (`NightOps`, `NightOpTask`). An agent is a campaign of one (kind `night`).
  - **When and against whom.** At dusk each village may send one against a village of another
    country within `warfare.night_ops_range` (700), or the base of a player its country is at war
    with. The chance is `warfare.night_ops_chance` (0.25), doubled in war.
  - **Allowed operations.** Theft with relations at −20 or worse. Arson and killing in hostility
    or war, or as revenge for 5 days after catching the other country's agent.
  - **The agent.** The village's idle poor villager, who keeps a quarter of the stolen emeralds,
    else a spare warrior.
  - **Abstract.** Success is skill (2 + gear/2) against 1 + the night watch + the golem.
    - **Success.** A theft takes up to 16 emeralds (or valuables). Arson burns about half of a
      household's goods at once, and the house itself catches fire when the village next loads
      (`Colony.pendingFires`). A killing takes a day-shift warrior, else the elder, else an adult.
    - **Failure.** The agent is caught (killed) 60% of the time; otherwise it slips away.
  - **A player's base** is never a record: the area around it is held loaded during the night
    (`world.SiteLoader`), and the agent works there live even with the player away.
  - **Live.** The agent walks to the treasury chest, a house bed (to light a fire nearby) or the
    nearest sleeper (warriors first), and acts. Someone awake who sees it within 16 blocks spots
    it, and the village's warriors attack it. An agent that is hurt runs, and at dawn an
    unfinished operation fails.
  - **Caught.** A caught agent is an incident (−8) and gives the victim the right to revenge. A
    player may kill a soldier or agent away from home without breaking the law.
- **War against the player** (`PlayerBases`, `PlayerIntel`, `PlayerRaid`).
  - **Presence.** Sampled every 2 minutes per 32×32 cell: 1 by day, 3 at night, 6 asleep, and 8
    for a logout.
  - **The base.** The top of the 12 most visited cells and the respawn point, scored by presence
    plus the player's home blocks there: the respawn bed 10, beds 5, chests 2, furnaces, crafting
    tables and doors 1.
  - **Strength score.** 2 + armor + 2 × attack + diamonds mined / 4 + mobs killed / 50 + 2 per
    villager or golem killed + 4 per golem built.
  - **Offensive.** Daily, a country at war with a player campaigns against the base when its
    strength times `warfare.player_offensive_ratio` (0.8) reaches the player's score plus 4 per loss
    inflicted.
  - **Coalition.** Allies of the attacker, or friends (relations 30+) whose capital has bad
    gossip about the player, declare war too (cause `coalition`).
  - **The storm.** The outpost goes up near the base. Soldiers fight the player, carry the base's
    chests off (player-placed ones first) and set the base on fire. The loot goes to the staging
    village's treasury. The campaign gives up after `warfare.player_campaign_days` (3).
  - **The player away.** During the storm the army holds the base and its own position loaded
    (`world.SiteLoader`, a ticket that expires by itself), so the base is looted and burnt for real
    even when its owner is far away.
- **Debug.** `/btv war` shows campaigns, war accounts and the nearest village's army. Subcommands:
  - `launch <pos>` sends a campaign;
  - `step` runs the hourly campaign step now;
  - `fortify` puts up every planned wall block in loaded chunks;
  - `army` shows and reassigns classes;
  - `recruit <n>` conscripts adults;
  - `night [theft|arson|kill]` sends every allowed night operation now (of that kind);
  - `horses <n>` sets the nearest village's stables;
  - `intel` samples players now;
  - `offensive` decides offensives against players now.
