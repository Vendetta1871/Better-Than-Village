# Demo scenes

Staged scenes for the README media, played by the dev client (`runClient`) again and again from the
same world snapshot. The director (`client/demo/DemoDirector`) is active only in dev runs and inert
in a release jar.

A scene builds its world once (`[setup]`), saves it as a snapshot under `run/demo/snapshots/` and then
plays the same way on every replay (`[play]`), each time from a fresh copy of the snapshot. The camera
is a fixed spot chosen in game. Nothing is spawned or handed out for the picture: the villages grow,
build, arm and fight the way the mod has them do it, the scenes only speed time up and set the stage.

## Running

```sh
demo/client.sh <project-dir> [scene[,scene...]] [--record] [--quit]
```

- No scene: the game starts as usual; `/demo` commands work in any world.
- `scene,...`: the scenes play right after the start (their snapshots are built first when missing).
- `--record`: `#shot` and `#record` write `run/demo/out/<name>.png` and `run/demo/out/<name>/` frames.
- `--quit`: the game closes when the scenes are done; the log has `[demo] PASS` or `[demo] DONE: N
  failed commands` (failed commands and `#wait-log` timeouts).

`BTV_JAVA_HOME` (a JDK 21) and `BTV_PROXY` (an HTTP proxy for Gradle) work as for the server tests.
`demo/gif.sh run/demo/out/<name> [--from S] [--to S] [--speed X]` turns recorded frames into `<name>.mp4`
and a `<name>.gif` of a part of it, sped up; each scene notes the cut its README gif uses.

## In game

- `/demo list`: the scenes, whether their snapshot is ready, their cameras.
- `/demo play <scene>`: replay a scene; `/demo loop <scene>` replays it until `/demo stop`.
- `/demo record <scene>`: replay with `#shot` and `#record` on.
- `/demo setup <scene>`: rebuild its snapshot (it is rebuilt by itself when its `[setup]`, or the
  setup of a scene it starts from, changed).
- `/demo cam [name]`: write where you are and where you look into the current scene as its camera
  (`main` by default). Fly there in spectator mode, F1 hides the HUD to judge the frame.
- `/demo goto [name]`: back to the camera; `/demo stop`: stop the scene.

## Scene files

`demo/<scene>.txt`:

```
// comment
@from village                        start from the snapshot of another scene
@camera main 180.5 96 160.5 -45 30   x y z yaw pitch, written by /demo cam
[setup]                              once, saved as the snapshot
...
[play]                               every replay
...
```

Lines are commands without the slash, run on the integrated server as the player with permission 4
(`@s` is the camera). Directives:

| Directive | Effect |
|---|---|
| `#sleep N` | wait N seconds |
| `#ticks N` | `tick sprint N` and wait until it is over |
| `#wait-log REGEX [timeout]` | wait for a log line or command reply (after the previous match) |
| `#until SECONDS REGEX :: COMMAND` | run the command every two seconds until a reply line matches |
| `#capture NAME REGEX :: COMMAND` | set a variable to the first group of the first matching reply line |
| `#set NAME EXPR` | set a variable |
| `#camera [name]` | put the camera there and hide the HUD |
| `#gui on\|off` | show or hide the HUD |
| `#shot NAME` | screenshot (recording only) |
| `#record NAME SECONDS` | start a clip at 15 fps (when recording; a replay just runs as long) |
| `#wait-record` | wait until the clip is done |

`{LX+16}`-style expressions use the variables: `LX`/`LZ` from the last reply with `[x, ~, z]` (as in
the server tests) and those set with `#set`. They are saved with the snapshot.

## Scenes

| Scene | Shows |
|---|---|
| `village` | The base: the nearest plains village of seed `btv-test` after a week of growth |
| `pit` | The miner closing the deep pit among the houses with a lid, from its edge inward (`pit.gif`) |
| `hero` | The village from above, the pit filled, its wall along the houses (`hero.png`) |
| `building` | A mason finishing a house, from its walls up to the roof (`building.gif`) |
| `lumberjack` | A lumberjack felling a tree (`lumberjack.gif`) |
| `herder` | The herder's pen, sheep lured in and bred (`herder.gif`) |
| `siege` | Five against five, a rider among the attackers, no golems: the breach, the defenders meeting it, the villagers in their houses (`siege.gif`) |

A few scenes set the stage where the village would need more days: `pit` puts some taxes into the
treasury (filling is public work it pays for), `building` the levies of logs and stone the next
houses are built of, `herder` makes one villager a herder and stocks the
wheat a farmer would bring and logs for the fence, `siege` recruits warriors and gives the attacker iron for its gear.
Nobody is spawned and no finished gear is handed out.
