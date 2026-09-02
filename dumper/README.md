This project runs real Monifactory, default mods, default configs.

Here we render and extract whatever we want, and generate the dump. All as part of a Gradle task.

Then other services can use that artifact and do whatever they want with it, without running the game alongside (the modpack takes a lot of RAM and CPU, mind you, how would you even deploy this otherwise).
And also without title screen music playing on a window you cant see :P