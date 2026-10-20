# 02 — Professions

## Overview

| Profession | Vanilla look | Works | Sells (from own chest) | Buys |
|---|---|---|---|---|
| Elder | cartographer | sells plots and empty houses, collects taxes, trades with other villages, governs | deed maps, empty houses, goods imported by caravan | empty maps (librarian), goods for the caravan |
| Warrior | weaponsmith | guards the territory in day and night shifts, fights in campaigns, night operations | — (paid by the treasury) | food, personal items |
| Farmer | farmer | crops on fields: wheat, carrots, potatoes, beets, sugar cane, pumpkins, melons | crops, bread, seeds, sugar cane | hoes, bone meal, food variety |
| Herder | shepherd | animals in pens, horses in stables, fishing as a fallback | meat, wool, leather, eggs, milk, horses, fish | feed (farmer), shears, buckets, leads, fences |
| Mason | mason | houses, walls, gates, roads, repairs | construction work (commissions) | stone (miner), wood (carpenter), glass, tools |
| Carpenter | fletcher | fells and replants trees, processes wood, builds lamp posts | logs, planks, sticks, doors, fences, chests, beds, apples, bows and arrows | axes, wool (beds), string |
| Smith | armorer | runs furnaces, makes tools, armor and weapons | tools, armor, weapons, glass, smelted metal | ores and coal (miner), sticks (carpenter) |
| Healer | cleric | cures zombie villagers, brews potions, supports allies, throws harmful potions at enemies | potions, healing | glass bottles, golden apples, Nether ingredients if offered |
| Miner | toolsmith | stone, ores, lapis, coal; flattens hills, digs tunnels, fills pits, demolishes illegal builds | stone, cobblestone, ores, coal, lapis, redstone, emeralds | pickaxes, torches, food |
| Librarian | librarian | books, maps, enchanting | books, empty maps, enchanted books, enchanting | paper (sugar cane from the farmer), leather (herder), lapis (miner) |

The chains are deliberate. No profession can work without buying from at least one other
profession, so the market always has something to trade.

### Changes from the old roles

The old docs and current code define the roles builder, guard, lumberjack, miner, farmer,
fisherman, rancher, elder, founder and citizen
(old [01-core §8](../docs/independent-villagers/01-core.md)).

| Old role | New role | Notes |
|---|---|---|
| Builder | Mason | Night lighting moves to the carpenter; terraforming moves to the miner. |
| Guard | Warrior | Gains classes (see [07](07-warfare.md)). |
| Lumberjack | Carpenter | Also processes wood and sells the products. |
| Fisherman | Herder | Fishing becomes the herder's fallback. |
| Rancher | Herder | Adds horses and stables. |
| Founder | Search party member | No longer a role (see [04](04-land.md)). |
| Citizen | Children only | Adults always have a profession; idle adults are conscripted. |

New roles: smith, healer, librarian.

### Reused mechanics

| Profession | Old reference |
|---|---|
| Carpenter (felling, replanting) | old [03 §2](../docs/independent-villagers/03-gathering-navigation.md) |
| Miner | old [03 §3](../docs/independent-villagers/03-gathering-navigation.md) |
| Farmer | old [04 §2](../docs/independent-villagers/04-food.md) |
| Herder fishing | old [04 §3](../docs/independent-villagers/04-food.md) |
| Herder pens | old [04 §4](../docs/independent-villagers/04-food.md) |
| Mason | old [05 §2–6](../docs/independent-villagers/05-construction.md) |
| Warrior | old [02 §3](../docs/independent-villagers/02-integration-defense.md) |
| Tool supply (all) | old [03 §1.6](../docs/independent-villagers/03-gathering-navigation.md), old [04 §6](../docs/independent-villagers/04-food.md) |

## Profession details

### Elder
- **One per village; an office, not a trade.** It is never inherited.
- **Succession.** When the elder dies, the adult with the best reputation in the village takes
  the office. Reputation is the sum of gossip other villagers hold about them. The
  new elder keeps their household and becomes a cartographer.
- **Pay.** The elder gets a fixed salary from the treasury.
- **Merchant.** The elder makes caravan trips to other villages ([06](06-politics.md)).
- **Ruler.** In the capital, the elder also rules the country.

### Warrior
- **Pay.** Warriors are paid wages by the treasury. They produce nothing and buy food at
  gatherings.
- **Gear.** Gear is treasury property, issued and returned (see [07](07-warfare.md)).
- **Classes.** Warriors can switch class freely. That is not a profession change.
- **Shifts.** Warriors work in two shifts, the day duty and the night watch
  ([07](07-warfare.md)).
- **Night operations.** Warriors, or the poorest villagers, are sent on theft raids and
  sabotage ([07](07-warfare.md)).
- **Conscripts.** Idle adults of other trades serve alongside them (see Conscription).

### Herder
- **Animals.** Keeps animals in pens (old mechanics) and horses in stables. Horses are sold to
  the treasury for cavalry.
- **Fishing.** When the village has unmet demand for meat (open buy orders at the last
  gathering) and the herder has too few animals to slaughter (pen population at or below the
  breeding minimum), the herder goes fishing until the stock recovers.

### Carpenter
- **Woodcutting.** Fells natural trees and replants them (reuses the old lumberjack).
- **Processing.** Turns logs into planks, sticks, doors, fences, chests and beds (with wool),
  and sells what they make.
- **Lamp posts.** Builds lamp posts along roads and squares: a fence column with a torch or
  lantern on top. This replaces the old torch-placing night lighting.

### Miner
- **Quarry yield.** Breaking stone gives an ore with a configurable chance by depth and biome,
  for example coal, iron, copper, gold, redstone, lapis, diamond (deep) and emerald (mountains
  only). Real ore blocks are still mined when found.
- **Terraforming.** Flattening hills, digging tunnels and filling pits ([04](04-land.md)).
  Spoil is kept in the miner's own chest and sold or used as fill.
- **Demolition.** Tears down illegal player builds in the territory to the ground. Legal
  buildings on player plots are never touched.

### Smith
- **Furnaces.** Smelts ores and sand (glass).
- **Crafts.** Makes tools, armor and weapons. Sells to households and the treasury.

### Healer
- **Zombie cures.** Cures zombie villagers the vanilla way: a splash potion of weakness, then a
  golden apple.
- **Support.** Sells healing and buff potions to the treasury for warriors. In fights, throws
  harmful splash potions at enemies.
- **Simplification.** Brews without Nether ingredients, but buys them when offered.

### Librarian
- **Books and paper.** Makes paper from sugar cane and books from paper and leather.
- **Maps.** Makes empty maps (paper and a compass) and sells them to the elder for deed maps.
- **Enchanting.** Enchants with lapis. Customers are the treasury (army gear), other households
  and the player through trades.

## Generation rules

1. **Guaranteed roster.** Every village has an elder, at least one warrior and at least one
   farmer. If the vanilla village is too small, the missing villagers are spawned.
2. **Converting vanilla professions:**

   | Vanilla profession | Becomes |
   |---|---|
   | farmer | farmer |
   | shepherd, butcher, leatherworker, fisherman | herder |
   | mason | mason |
   | fletcher | carpenter |
   | armorer | smith |
   | toolsmith | miner |
   | weaponsmith | warrior |
   | cleric | healer |
   | librarian | librarian |
   | cartographer | elder; extra cartographers become librarians |
   | nitwit, unemployed adults | see below |

   Freshly generated villagers are mostly unemployed, so "all unemployed become warriors" would
   give a village of warriors. An unemployed adult (or nitwit) instead takes, in this order:
   1. a missing guaranteed trade (farmer, then warrior);
   2. a free vanilla workstation in the village, mapped through the table above;
   3. the first trade the village still lacks, in the order farmer, warrior, carpenter, mason,
      miner, herder, smith, healer, librarian;
   4. warrior.

   The elder is chosen before the trades: a cartographer if there is one, otherwise the adult with
   the best reputation (see Elder).
3. **Starting state.** Each adult gets a house (an existing bed), a home chest and starting
   capital ([03](03-economy.md)).

## Fixed professions and inheritance

- **Fixed for life.** A villager keeps their profession forever. The **elder** office (see above)
  is the only real change of profession.

### Conscription

The village never has unemployed adults: they serve in the army. Conscription is **temporary**
and does not change the profession.

- **Who.** An adult whose trade has earned nothing for several days: the market is saturated, or
  there are no trees or ore within reach.
- **What happens.**
  - They serve as a warrior and get a warrior's wage.
  - They keep their trade profession and look. Children still inherit the trade.
- **Demobilization.** When their trade pays again (open bids for their goods at gatherings, or
  the resource is back), they return to it.

Conscription must not ruin the country, so it has a budget:
- **The tax base does not shrink.** Taxes are a share of income, and conscripts had no income.
- **The army is capped by the treasury.** A villager is conscripted only while the treasury can
  pay all warriors' wages for several days ahead (default 5). If it cannot, conscripts are
  demobilized first, before professional warriors.
- **Wages go back into the economy.** Conscripts spend them on food, which is demand for farmers.

**When the army is full.** An idle villager the treasury cannot afford is an **idle poor**.
They do not starve to death. Instead they:
1. live on credit up to their limit ([03](03-economy.md));
2. are the first volunteers for night operations ([07](07-warfare.md));
3. are the first members of the next search party: poverty pushes colonization
   ([04](04-land.md));
4. are never part of a candidate pair for a birth.
- **Inheritance.** A child gets the profession of one parent, 50/50 by default. It is chosen
  when the parents commission the house, before the birth, because the house comes with the
  workplace for that profession ([03 §Births](03-economy.md)).
  A config option instead favours the profession with the higher market demand. If one parent
  is the elder, the child gets the other parent's profession.
- **Personality.** At birth, a child gets a greed trait `g` in roughly ±0.3 (see 03). It is
  drawn around the average of the parents' traits.

## Children

Children do no work. They play as in vanilla. From birth a child lives in their own house,
paid for by the parents ([03 §Births](03-economy.md)). When the child grows up, the house,
chest and profession are already in place.
