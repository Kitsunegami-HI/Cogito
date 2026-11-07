---
id: minecraft:ender_eye
targets: item:minecraft:ender_eye
summary: throw it to find the stronghold; it may break, and 12 can fill an End portal
---
Hold it and right-click in the air. It flies toward the nearest stronghold, then drops or shatters. Turn to where it flew and walk that way, then throw again. When it flies downward you are above the stronghold: dig down carefully.

Each thrown eye has about a 1 in 5 chance of breaking. To light the portal, right-click each empty frame with an eye.

Aim away from nearby blocks when throwing: the click goes to whatever is under the crosshair first.

```actions
{"throw": {"summary": "throw one eye and report where it is flying", "params": [],
  "steps": [{"fn": "equip", "args": {"item": "minecraft:ender_eye"}},
            {"fn": "look", "args": {"yaw": 0, "pitch": -30}},
            {"fn": "activateItem", "args": {}},
            {"fn": "waitForTicks", "args": {"n": 2}},
            {"fn": "deactivateItem", "args": {}},
            {"fn": "waitForTicks", "args": {"n": 4}},
            {"fn": "entities", "args": {"radius": 16, "type": "minecraft:eye_of_ender"}}]}}
```
