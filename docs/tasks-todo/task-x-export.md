# Task: Exports

We currently have exports in JSON and CSV for selected chunks which include code like:

```json
{
  "dimension": "world#minecraft:overworld",
  "chunks": [
    {
      "chunkX": -8,
      "chunkZ": 5
    },
    {
      "chunkX": -8,
      "chunkZ": 4
    },
    {
      "chunkX": -7,
      "chunkZ": 4,
      "inhabitedTime": 3321
    },
    {
      "chunkX": -6,
      "chunkZ": 4,
      "inhabitedTime": 3400
    },
    {
      "chunkX": -6,
      "chunkZ": 3,
      "inhabitedTime": 3543
    },
    {
      "chunkX": -12,
      "chunkZ": 8
    },
    {
      "chunkX": -13,
      "chunkZ": 8
    },
    {
      "chunkX": -14,
      "chunkZ": 8
    },
    {
      "chunkX": -14,
      "chunkZ": 7
    },
    {
      "chunkX": -13,
      "chunkZ": 7
    }
  ],
  "worldName": "world",
  "worldSeed": -8408138640175831000
}
```

The purpose of these exports is to enable us to use these in the other tools to delete these chunks. See `README.md` for more on this. Now I feel like we should probably do the following:

1. Look online for other mods or command line tools which we could use to trim blocks and the formats they accept.
