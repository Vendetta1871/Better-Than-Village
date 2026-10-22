# 05 — Law

## Jurisdiction

- **Territory.** The full law below applies. The player's own plots are exempt from the
  building rules.
- **Domain.** No rules for the player. The country only opposes other countries there.
- **Outside.** Nothing applies.

## Offences (territory, peacetime)

| Offence | Kind | Detail |
|---|---|---|
| Weapon in hand | continuous | Swords, bows, crossbows, tridents and maces in either hand. It is a config item tag. **Axes are not weapons.** |
| Building or breaking blocks | continuous | Anywhere outside the player's own plots. Whatever was built is an **illegal build**: the miner demolishes it to the ground even if the player stopped in time ([04](04-land.md)). |
| Killing animals | one-shot | Passive farm animals inside the territory. |
| Attacking a villager, child or the village golem | one-shot | |
| Theft | one-shot | Taking from a home chest, the treasury or village buildings. Needs a witness (current implementation, commit `f80366d`). **Not theft:** a player seizing goods from the chest of a defaulted debtor, up to the debt, when the treasury could not pay either ([03](03-economy.md)). |

One offence does not depend on where the player is:

| Offence | Kind | Detail |
|---|---|---|
| Unpaid debt | continuous | A debt note signed by the player is overdue ([03](03-economy.md)). The creditor's village warns the player. The grace period is longer (default 1 in-game day), and paying it off clears the offence. |

Every other offence must be **noticed by a witness**: a villager with line of sight within range.
Unwitnessed offences have no effect. Witnesses spread what they saw through vanilla gossip.

## Warnings and escalation

- **Continuous offences.** The nearest villager (or the elder, if near) warns the player in
  chat. The player has a **grace period** (default 30 s, configurable up to 60 s) to stop or,
  for building, to buy a plot. Still offending after it means **war**.
- **One-shot offences.** An immediate warning. A **second offence while the warning is still
  active** means **war**.
- **"Active warning" uses vanilla gossip** instead of our own counters:
  - Warnings add `MINOR_NEGATIVE` gossip; attacks add `MAJOR_NEGATIVE`, which vanilla already
    does.
  - A warning is active while the village's total gossip of that kind about the player is above
    a threshold.
  - Vanilla gossip decays over in-game days and spreads between villagers. The warning
    "cooldown" and "the whole village heard about it" come for free.
  - A short per-village timer only prevents spamming the same warning.
- **Price effects.** The same gossip already changes the player's trade prices in vanilla, so
  minor offences cost money before they cost war.

Implementation notes (`law` package):
- **Witnesses.** An awake villager of the village within 16 blocks with line of sight, or within 7
  blocks (3 when the player sneaks) through walls; the village golem counts too. For a war, the
  country's members spot the player within 24 blocks.
- **Warnings.** The speaker is the elder if within 16 blocks, else the nearest witness. A warning
  adds 25 gossip (`MINOR_NEGATIVE`, `MAJOR_NEGATIVE` for attacks) to every witness and to the
  elder. "Active" means the minor plus major negative gossip of the loaded members reaches
  `law.warning_gossip` (20), so one warning lasts about a day. Offences of the same kind within
  3 s of a warning belong to it.
- **Continuous offences.** A sighting more than 20 s after the previous one starts a new episode
  with a new warning; a sighting at least `law.grace_seconds` after the episode began means war.
  Weapons are checked once a second; placing or breaking blocks are sightings. Continuous
  offences are suspended while the village fights raiders or monsters.
- **Building.** Placements count for structural blocks (full cubes, doors, beds, chests, stairs,
  slabs), not utility blocks (torches, crafting tables, …). Breaks count for any block that takes
  time to break, except the player's own blocks. A building warning marks the player's blocks in
  that village as illegal builds for the miner.
- **Theft.** Taking 8 or more items, or a valuable one, is war at once.
- **Animals.** The farm animals in the `protected_animals` entity tag, unless the player keeps
  them: named, leashed, tamed, on the player's land or in a player-built enclosure.
- **Debt.** The morning after the due date the player is warned; a day later the default stands
  and the creditor's country declares war. Paying the note (the elder still takes debt payments
  at war) ends a debt war if nothing else is overdue to that country.
- **War.** Stored per country and player. Players are never alarm attackers any more: warriors and
  golems attack a player exactly while the player is at war with their country and shows no white
  flag. While a hostile player stands in a territory, civilians take shelter as in a raid.
- **Surrender.** The flags are the `surrender_flags` item tag. The ceasefire lasts while the flag
  is seen (3 s after the last sighting); the nearest elder walks up to the player.
- **Treaty price.** `law.treaty_base_price` (16) times country strength over player strength,
  clamped to a quarter to four times and to 64 emeralds. Country strength is the ruler's measure
  ([06](06-politics.md)): per village 4 per warrior, 3 per wall ring, 4 for a golem near a live
  village, 1 per 16 treasury emeralds and half the population. Player strength: 2 + armour + 2 × attack damage + 4 per loss
  inflicted. The country refuses for 5 minutes after killing the player while it has killed the
  player more often than it lost villagers.
- **Weariness.** `law.weariness_losses` (4) villagers or golems lost, or `law.weariness_days` (5)
  days of war with a loss. The elder then trades a white paper for up to 2 + 2 × losses emeralds
  from the treasury, plus the treaty book.
- **Peace.** The country's loaded villagers forget their negative gossip about the player. The
  truce (`law.truce_days`, 3) is recorded; in phase 3 new offences still lead to war, the truce
  only matters to the offensive wars of phase 5.
- **Tests.** `/btv bot` drives a fake survival player (`debug.TestBot`) in headless tests:
  hold, place, break, attack, steal, trade with the elder; `/btv law` shows wars and gossip.

Reuses from the `legacy` port: the land-notice state machine and griefing/theft detection. Hostility points are replaced by
gossip.

## War against the player

- **Declaration.** The elder announces war in chat. War is declared by the **country**, not just
  the village.
- **Phase 3 (defensive).**
  - Every warrior and the golem of the country attacks the player on sight in that country's
    territories.
  - Villagers refuse to trade.
  - Civilians take shelter, as in the `legacy` port.
- **Phase 5 (offensive).** The country sends armies, builds outposts near the player's base and
  tries to storm it. Other countries may join, based on the player's strength score
  ([07](07-warfare.md)).

## Surrender

A player holding **white paper**, a **white banner** or a **white carpet** in the main hand,
visible to the country's warriors, is surrendering:
- **Ceasefire.** Warriors stop attacking them while they hold it and do not attack.
- **Terms.** The nearest elder (or a warrior on campaign, who leads them to an elder) offers
  the peace terms as a trade (see below).
- **Perfidy.** Attacking while showing the white item is perfidy: surrender is not accepted from
  this player for a configurable time.

## Peace treaties

- **How peace is bought.** Through the **vanilla trade screen** with an elder of the warring
  country (any village of it). The offer is "emeralds (and possibly goods) → written book".
- **The book.** It lists the terms: parties, date, price, truce duration. It is only a
  receipt and collectible. The treaty itself is stored on the server.
- **Price.** It depends on the balance of strength:
  - **Player stronger** (strength score, recent battle losses of the country): a cheap
    treaty.
  - **Country stronger**: an expensive one. The country may refuse peace entirely if it is
    winning.
- **The other direction.** The country does not have to lose its whole army to ask for peace.
  Its elders decide when they have **had enough**, using the war-weariness rule
  ([07](07-warfare.md)): significant losses, a drained treasury, a long war. Then an elder
  offers a treaty where the **player is paid**: the book costs nothing and the trade gives
  emeralds. The player is external, so they cannot conquer villages.
- **Truce.** After a treaty, the country cannot declare war on the player again for the truce
  duration, except for new offences.
