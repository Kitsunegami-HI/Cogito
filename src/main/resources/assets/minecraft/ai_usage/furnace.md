---
id: minecraft:furnace
targets: block:minecraft:furnace, item:minecraft:furnace
summary: smelts ore and cooks food; window slots are 0 input, 1 fuel, 2 output
---
Open it with `openFurnace`, put the input in slot 0 and fuel in slot 1 with `windowPut`, close it, wait, then open it again and take slot 2 with `windowTake`.

One coal smelts 8 items and takes 10 seconds per item. Logs and planks also burn, but for fewer items. The block keeps working after you leave as long as its chunk is loaded.

```actions
{"load": {"summary": "load one input item type and fuel into a furnace in reach", "params": ["x", "y", "z", "input", "fuel"],
  "steps": [{"fn": "openFurnace", "args": {"x": "$x", "y": "$y", "z": "$z"}},
            {"fn": "windowPut", "args": {"slot": 0, "item": "$input"}},
            {"fn": "windowPut", "args": {"slot": 1, "item": "$fuel"}},
            {"fn": "closeWindow", "args": {}}]},
 "collect": {"summary": "take what has finished from a furnace in reach", "params": ["x", "y", "z"],
  "steps": [{"fn": "openFurnace", "args": {"x": "$x", "y": "$y", "z": "$z"}},
            {"fn": "windowTake", "args": {"slot": 2}},
            {"fn": "closeWindow", "args": {}}]}}
```
