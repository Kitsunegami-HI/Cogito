---
id: minecraft:crafting_table
targets: block:minecraft:crafting_table, item:minecraft:crafting_table
summary: 3x3 crafting; needed for tools, armor and most blocks
---
A crafting table gives a 3x3 grid. Recipes that fit in 2x2 can be crafted from the inventory without one.

Carry one or keep one near your base: `craft` looks for a table within reach for 3x3 recipes, so place it first with `placeBlock`, then call `craft`. Break it with an axe and take it with you when you move on.

```actions
{"place": {"summary": "place a crafting table you carry into an empty cell", "params": ["x", "y", "z"],
  "steps": [{"fn": "placeBlock", "args": {"x": "$x", "y": "$y", "z": "$z", "item": "minecraft:crafting_table"}}]}}
```
