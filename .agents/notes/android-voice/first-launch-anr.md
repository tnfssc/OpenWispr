---
name: First launch after install can hang (pre-existing)
description: Fresh install + keyboard selected + first app open ANRs in Subtypes.addDefaultSubtypesIfNecessary
---
Seen on API 36 emulators, including a build from before the inline/floating-mic work, so it is not caused
by those changes. Main thread blocks in `LatinIME.onCreate -> Subtypes.addDefaultSubtypesIfNecessary ->
addLanguage -> setSettingBlocking` (runBlocking on a DataStore write) while the coroutine workers sit idle.
Force-stopping and reopening works, and later launches are fine. Not investigated further.
