# Task: Chunk Selector Dimensions

The chunk selector does not currently differentiate between different dimensions. If I select some chunks in the overworld and then switch my map to the nether, I still see those chunks as selected. And vice versa. Obviously this is the same with the end dimension. 

Selected chunks should be scoped to the relevant dimension.

This doesn't just apply to how they're visually displayed also when I go to export them as JSON/CSV, The export just includes all of them, with no indication of which dimension they're in. 

Now the simplest solution to this would simply be to clear the chunk selection whenever we switch to a new dimension And assume that when a user is selecting chunks in order to export them for trimming. But they will do it one dimension at a time. The downside to this is that in some cases we will have multiple maps for the same dimension. For example, on many of my servers, I have two blusmap maps for the Nether: one for the nether roof and one for "below". And I probably want to be able to switch between these when selecting chunks and have those chunks persist between those map switches because they're both just different views of the same dimension. So like I would probably start selecting blocks to trim on the nether roof and then I would go and swap to the map for lower down in the nether and check that I hadn't selected any blocks that I shouldn't have. You could imagine a similar situation in the overworld where I might have one map for the surface and another map for a certain level underground. And I would want to swap between them. So what I'm really saying here is that these should be per dimension, not necessarily per bluemap map.

The final thing here is that when we have the JSON export, I feel like we should include the following in the JSON itself when it's exported at the top level:

- Dimension
- World Seed
- (if possible) Name of the world

And then we'll include the chunk data.
