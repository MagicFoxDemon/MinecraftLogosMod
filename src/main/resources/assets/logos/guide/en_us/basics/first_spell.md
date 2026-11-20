---
title: Your First Spell
icon: logos:inscription_table
order: 3
---
## Placing a Page
Right-click an [Inscription Table](item:logos:inscription_table) with a blank spell page to lay it onto the table. Right-click again to open the **Spell Workshop**, or sneak-right-click to take the page back.

## The Spell Workshop
The node canvas fills the left side of the workshop, with the spell overview on the right. A spell is built by wiring **nodes** in sequence:
- **spell**: The entry point of the graph, setting the spell's name, injected mana, and trigger. Each spell graph has only one.
- **projectile**: Forms a magical bolt that flies along a path.
- **On hit**: An event port on the projectile that runs whatever actions are wired to it when an entity is struck.
- **damage**: Deals a chosen amount of elemental damage to a target.

Wire them together as `spell → projectile → (On hit) → damage` to create the simplest spell, "First Bolt." You can also select **First Bolt** from the Templates list on the right and click "Load template."

The overview panel checks live whether the spell holds and estimates its mana cost per cast. Once the graph is valid, click the wax-sealed "Write the spell page" button at the bottom to inscribe it.

Write with care: a spell follows exactly what is written and can harm its own caster just as easily as an enemy.

## Hanging on a Wand
Sneak-right-click the table to retrieve your inscribed page. Hold a [wand](item:logos:oak_wand), sneak-right-click to open its spell slots, insert the page, and you are ready to [charge and cast](casting_and_mana).

## Learning Runes
You start knowing only **projectile**, **damage**, **Physical**, and **Forward**. Other runes, elements, and motions are learned by reading **Rune Books** found in ruin chests or bought from Warlock villagers.

Unlearned runes appear garbled in the workshop and cannot be written onto pages, though pages already written by others can still be cast and copied normally. Creative mode grants access to all runes.
