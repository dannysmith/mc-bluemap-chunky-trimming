# Task: Minor Improvements

## 1 - Inhabited time HUD

- [ ] Move it to bbottom center of the screen
- [ ] Ensure it only shows when the inhabited time markers are toggled on

## 2 - Selected Chunks Color

Currently selected chunks when in chunk selection mode show up as orange. I would like to instead use a colour here which is significantly different to any colours that we use in the inhabited time heat map or in any of the other overlays. Because it can be hard to differentiate between the selected chunks in selection mode and ones that are just "orange" level inhabited time. This needs to just be a colour change. if it's possible with blue map or with how we do this at all to increase the width of the solid border around these chunks we might want to consider doing that too. Or alternatively to make this easier we might want to consider colouring these with a kind of hatched pattern across them between more opaque and more transparent solid bars to make it easy to differentiate what's selected whether or not they are coexisting with other chunk overlays. 

## 3 - Enable only in "flat" mode

currently our chunk selection and our two heatmap overlays wor in all view modes (perspective, flat and free/spectator). But obviously the markers we draw because they're chunk based don't actually line up or work properly in any view except for "flat". So I feel like what we should do here is only show any of this stuff in flat mode. And that means doing two things:

1. Only enable the chunk selector in flat mode, including the button in the top left to toggle it on and off. We need to gracefully handle obviously what happens if someone switches their mode while this is on. I guess it should all just disappear until they switch back to flat mode. 
2. Only show our various heat map markers when in flat mode. I imagine that we'll have to just hide these on the actual map, even if they're toggled on in other views? We should look for a idiomatic blue map way of doing this, if it exists. 

## 4 - Chunk Selector: Better Selection

Currently you can select a chunk by holding command and clicking, and deselect a chunk which is already selected by doing the same. This works great, but it's not efficient for selecting large amounts of chunks. I would like to be able to hold command and then kind of click and move my mouse around and have all of the chunks that I move my mouse over while I'm holding command be selected (or deselected if selected already)

Also, I guess it would be kind of cool to be able to select like large squares of chunks by I I don't know holding some key combination and selecting like like a lot of chunks all at once by dragging out a square box and then when I let go like all of those are are are are are are are are are are are selected or deselected depending on what they were before, right? That probably wants to be a separate key combination to do that, though. 

While we're looking at this, it might also be worth us looking at generally how we handle selection and deselection and and you know, events clicking on things and stuff to make sure that we're doing it in a sensible and and performant way. 
