# Task: Exports

We currently have exports in JSON and CSV for selected chunks which include code like:

```json
{
  "chunks": [
    {
      "chunkX": -9,
      "chunkZ": 2,
      "inhabitedTime": 161,
      "tileEntities": 0,
      "hasPlayerBlocks": false
    },
    {
      "chunkX": -9,
      "chunkZ": 3,
      "inhabitedTime": 135,
      "tileEntities": 0,
      "hasPlayerBlocks": false
    }
  ]
}
```

The purpose of these exports is to enable us to use these in the other tools to delete these chunks. See `README.md` for more on this. Now I feel like we should probably do the following:

1. Remove `hasPlayerBlocks`. I don't think this is important here. The rest can stay And it's likely to be useful in other applications.
2. look online for other mods or command line tools which we could use to trim blocks. 
