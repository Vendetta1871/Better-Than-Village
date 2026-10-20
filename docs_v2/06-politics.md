# 06 — Politics

## Countries

- **At generation**, every village is its own country, and its own capital.
- **New villages.** Villages founded by a country's search party join that country.
- **Conquest.** Conquered villages join the conqueror.
- **The capital** is the village the country comes from. If the capital is lost (conquered or
  destroyed), the next oldest village of the country, by founding order, becomes the capital.
  A country with no villages left is gone.
- **The ruler** is the elder of the capital. They decide on war, peace, alliances and orders.
- **The player** never belongs to a country.

## Orders

The ruler can issue orders to the other villages of the country:
- **Redistribution.** Move resources from one village's treasury to another's, without
  payment: food in a famine, materials for walls, emeralds. Only treasuries are affected,
  never household chests. Delivered by caravan.
- **Mobilization.** Send warriors (and the village golem) to a gathering point for a campaign
  ([07](07-warfare.md)).
- **Higher taxes, temporarily.** A share of each village's treasury goes to the capital.

## Loyalty and rebellion

Each non-capital village has a **loyalty** score.

| Lowered by | Raised by |
|---|---|
| Distance from the capital | Shared victories |
| Resources taken by orders | Redistribution received |
| Losses in the country's wars | Long peace |
| Conquest (conquered villages start low) | Time since joining |

- **Disobedience.** Below a threshold, the village's elder may **ignore orders**. Each ignored
  order lowers the ruler's patience.
- **The ruler's response:**
  - **tolerate**: patience slowly recovers; or
  - **suppress**: a punitive campaign against the village.
- **Outcome of suppression.**
  - If the ruler wins, the rebel village is "conquered" back with low loyalty.
  - If the rebels win, the village becomes an **independent country** with itself as capital.

## Diplomacy

Relations between two countries:

| State | Meaning |
|---|---|
| **Alliance** | Common enemy or good trade (see below). |
| **Peace** | The default. Theft raids are still possible against countries with bad relations. |
| **Hostility** | Relations are terrible, but war has not been declared. Sabotage (arson, killing) is allowed. It is the step before war; a treaty or improving relations can bring it back to peace. |
| **War** | Open campaigns ([07](07-warfare.md)). |

Relations are a score between the two countries, fed by trade, debts, incidents and the gossip
their villagers hold about each other's villagers.

- **Alliances** form when two countries **share an enemy** or have **good trade**: steady
  caravan trade and no defaulted debts between them. They are recorded as a treaty, abstract. An
  ally is expected to join defensive wars. Refusing lowers relations, and if relations fall far
  enough the alliance breaks.
- **Causes of war:**
  1. **Defending an ally.** An ally was attacked.
  2. **Repelling an attack.** The country itself was attacked.
  3. **Conquest.**
     - **Resources and people**, for example a country with no miners that cannot buy ore.
     - **Territory**: a country with no free land to expand into.
  4. **Border dispute.** A country needs land for a search party, and the owner refused to sell
     it or the buyer could not pay.
  5. **Unpaid debt.** A village or country has defaulted on a debt note to this country
     ([03](03-economy.md)).
  6. **Repeated incidents.** Agents of another country were caught again and again
     ([07](07-warfare.md)). A single caught thief is answered with **revenge** at night, not with
     an immediate war.
  7. **Rebellion.** Suppressing a rebel village of its own.
  8. **Player offences** ([05](05-law.md)).
- **Deciding on war.** The ruler compares strength:
  - warriors, gear tier, the golem, wall rings and treasury on each side;
  - allies who would join.

  The ruler declares a conquest war only with a clear advantage.
- **Peace between countries.**
  - **When.** A side asks for peace when its elders have **had enough**: war weariness
    ([07](07-warfare.md)), not necessarily the loss of the whole army.
  - **Terms**, negotiated abstractly:
    - tribute (a one-time payment, or a share of taxes for N days);
    - transfer of border chunks or whole villages;
    - writing off debts;
    - a plain truce.

## Border changes

Domains belong to countries and change only by treaty or war ([04](04-land.md)). A typical case
is a search party that wants land inside a foreign domain.
1. **Offer.** The country offers to buy the border chunks, in emeralds or debt notes. The price
   is based on the chunk count and the owner's land value.
2. **Decision.** The owner **accepts or refuses**, even if the price can be paid. The decision
   depends on relations, strength and how much free land the owner has left.
3. **Accepted.** The chunks change owner.
4. **Refused, or no money.** The buyer may go to war over the land, or wait.

## Conquest

When a village's warriors are all destroyed and the attacker holds its bell area:
- **People.** Surviving villagers become **citizens of the winning country**. They keep their
  houses, chests and professions. This is why wars over resources make sense: a country without
  miners can take some.
- **Treasury.** It goes to the winner as loot. Debt notes the village held now pay the winner.
- **Domain.** The village's share of the domain goes to the winner.
- **Elder.** The old elder is deposed. The adult with the best reputation becomes the new elder,
  under the new ruler.
- **Loyalty** starts low.
- **Walls.** Rings damaged in the siege are repaired by the new owner's masons.
