---
id: logos:inscription_table
targets: block:logos:inscription_table, item:logos:inscription_table
summary: writes a Logos spell onto a blank spell page (术式载页); the book sprite (书灵) can write the spell from a plain request
handler: dev.logos.compat.aiplayer.AiPlayerActions
---
Logos has no ready-made spells. A spell is a short program in the Logos language, written onto a spell page at the inscription table (法式编纂台) and cast from a wand (法杖) that carries the page. You never need to draw nodes in the table's editor: the actions here do the whole table visit, so one step with one action is the whole job. Walking to the table, laying the page down and taking it back are part of the action; plan no steps for them. The editor opens for a moment on the way, because the server takes a spell only from someone who opened it, and closes again by itself. In a place you may not change, such as under a claim or spawn protection, the action fails with the game's own words about why.

Three ways to get a spell written, best first:

1. `write_spell` with a request in plain words, such as "a fireball that explodes where it hits" or "heal me when I am hurt". The book sprite (书灵), Logos's own spell-writing AI, turns it into source and the page is inscribed in one go. It needs a sage endpoint set up by the player or offered by the server: when `sage_unavailable` comes back, use the next ways. `ask_sage` alone returns the source and the sprite's words without writing anything.
2. `inscribe` with `example`: copy one of the example spells. `examples` lists them with their id, what they do, the mana they inject and the page grade they need.
3. `inscribe` with `source`: your own spell in Logos source. `check` compiles source without writing anything and reports mana, trigger, intents or the compiler's error. Prefer 1 and 2 unless you know the language.

Pages come in grades (基础 basic 50, 晶辉 crystal 150, 回响 echo 400, 炼成 transmuted 800, 白垩 chalk 1600, 天穹 firmament 3200, 贤者 sage 6400, 无相 principle 12800 mana per cast). A spell that asks for more than its page holds is still written, but a cast injects only the page's capacity. Inscribing needs a blank page: item logos:spell_page is the basic grade (paper + gold nugget), crystal_spell_page and so on the others. The action picks the lowest carried grade that fits, or the one named in `page`, and fetches one with obtain when none is carried, so plan no step for that either. The result is a written page named after the spell (logos:inscribed_page for the basic grade, crystal_inscribed_page and so on); its manual, logos:spell_page, says how to hang it on a wand and cast it.

Which table: the nearest within 48 blocks, or the one at x, y, z. A page already lying on it goes into the inventory first. When none stands near but one is carried, the action puts it down and takes it back afterwards. Only when there is neither, obtain logos:inscription_table first (page + book and quill + lectern), as a step of its own. Never obtain another table when near in the state says one stands close by.

Done looks like: a written page with the spell's name is carried, and one more than before (has_item logos:inscribed_page N for the basic grade).

```actions
{"examples": {"summary": "list the example spells: id, name, what they do, mana, trigger and the page grade each needs", "params": [], "read": true},
 "check": {"summary": "compile Logos source without writing it: name, mana, trigger, intents, or the compiler's error", "params": {"source": "the full (spell ...) source"}, "read": true},
 "ask_sage": {"summary": "ask the book sprite to write a spell from a request; returns the source and its words, writes nothing", "params": {"request": "what the spell should do, in plain words", "source": "optional: an existing spell's source for it to change"}, "read": true},
 "inscribe": {"summary": "go to the table, lay a blank page on it, write the spell onto it and take the page back", "params": {"example": "an example id from examples, or", "source": "the full Logos source", "x": "optional: the table; else the nearest", "y": "", "z": "", "page": "optional: item id of the blank page to use, else the lowest carried grade that fits"}},
 "write_spell": {"summary": "ask_sage and then inscribe, in one go; the result names the spell", "params": {"request": "what the spell should do, in plain words", "x": "optional: the table; else the nearest", "y": "", "z": "", "page": "optional: item id of the blank page to use"}}}
```
