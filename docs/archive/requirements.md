# Initial Requirements

Okay, I want to do some research here. Here's what I'm thinking of:

I currently have a Minecraft world on a WiseHosting server. Me and my friend Cam have been playing on this for a couple of years now and we've never done any chunk trimming. No, I think it's beyond doubt that Chunky is the go-to for this for server worlds (see https://github.com/pop4959/Chunky/wiki/Trimming-chunks).

We also have Bluemap installed on the server.

And what I would like to investigate is the possibility of building a bluemap plugin which adds some kinda overlay showing chunks and somehow color-coding the chunks based on whether we've placed any blocks in them and how much time players have spent in them.

We'd be able to select (and deselect) chunks by clicking/shift clicking them.

Then we'd somehow be able to click a button in Bluemap which generated the correct chunky selection/trim commands to remove them. the blue map plugin or mod doesn;t need to write any MC data (we'll just rn the commands in Chunky). It's basically a way for us to select a bunch of chunks which we want to trim away and then get back whatever we need to run in chunky to do that. Bluemap supports showing chunk borders by default.

So reallly this has three main features:

1. Some kinda overlay showing player time in each chunk.
2. Some kinda overlay showing whther we've placed blocks in the chunk or not.
3. A way to visually select/deselect chunks for trimming and have that generate the correct commands for Chunky.

If possible, I would prefer this to be a pure Blue Map plugin which uses the existing bluemap data where it can and reads the relevant minecraft data direct from disk where needed (ie. we don't need to have a MC mod installed and running on the server). But if the best call of action is to develop a mod that works with a plugin, then let's do that too. 

So your job now is to go away and conduct research online about bluemap plugins, chunky trimming, any existing solutions to this problem, other tools which do a similar job but not on bluemap/server (eg. MCA Selector), Any blue map plugins which may do something similar that we could lean on (eg overlays), and exactly what minecraft data we'd need access to to know about player time in chunk and whether blocks have been placed there (and how we could access that data in a bluemap plugin).

At this stage I'm not looking for an implementation plan, I'm looking for a starting point to begin thinking about whether this is worth doing or not, how difficult it would be, and what our various options and approaches are. So I'd like you to take your time and be thorough.
