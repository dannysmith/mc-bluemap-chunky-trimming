# Task: Player-modified markers have many false positives

**Status:** Confirmed as false positives from naturally-generated blocks in structures, not a coordinate bug. The `BlockClassifier` player-block list includes blocks that appear naturally in villages, temples, mineshafts, etc. (e.g. `minecraft:chest`, `minecraft:torch`, `minecraft:rail`, `minecraft:brewing_stand`).

**Possible approaches (not yet implemented):**
- Require `inhabitedTime > threshold` alongside player blocks to filter out unvisited structures
- Exclude blocks common in generated structures from the default list
- Use a tiered classification (strong signals like concrete/shulker boxes vs weak signals like chests/torches)

## Desired Behaviour

The ideal behavior for this feature is that we somehow are able to know whether there is a player-placed block in a certain chunk. If it's easier to ignore blocks modified by a player (ie removed blocks) and only look for those which have actually been placed by a player then let's do that.

If there's a better way of actually noting if a player has modified a chunk at all then let's do that.

If there is no reasonable way to achieve this without getting false positives, I would rather remove the feature altogether.
