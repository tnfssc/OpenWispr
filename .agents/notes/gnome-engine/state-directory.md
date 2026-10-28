---
name: Engine unit owns ~/.local/state/openwispr via StateDirectory
description: Why the engine service declares StateDirectory and what broke without it
---
Recordings go straight into `~/.local/state/openwispr/failed-audio` so speech survives an engine restart
(added with failed-speech recovery). The engine unit runs with `ProtectHome=read-only` and only listed
`.config`/`.cache` as writable, so every Start failed with
`create recorder temp file: mkdir ~/.local/state/openwispr: read-only file system` in gnome-v0.4.6 and
earlier. `StateDirectory=openwispr` (mode 0700) has systemd create the folder and make it writable inside
the sandbox. Any new path the engine writes needs the same treatment in the unit.
