# HyYap
TTS and voice chat utilities for hytale modders
## Overview
HyYap provides simple access to text-to-speech engines (currently only DECtalk) as well as means of broadcasting audio to players through voicechat system.

## Quick start

HyYap is available through [Hytale Modding Maven repo](https://maven.hytalemodding.dev/#/snapshots/icu/azim/hyyap/HyYap).

Add a dependency in your manifest.json:
```json
"Dependencies": {
    "icu.azim:HyYap": "*"
},
```

Then simply use it
```java
HyYapPlugin.getInstance().getDectalk()
    .speakAndEncode(message)
    .thenApply(frames -> HyYapPlugin.getInstance().getBroadcastThread()
        .broadcastAtSpeaker(senderUUID, senderNetworkId, frames, receivers));
```
See documentation for more info (to be written but everything you need is in BroadcastThread anyway)
