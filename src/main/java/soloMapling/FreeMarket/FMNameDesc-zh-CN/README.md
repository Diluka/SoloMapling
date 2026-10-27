# FMNameDesc-zh-CN

Simplified Chinese word lists for free-market shop names / descriptions, resolved by
`FMShopDescGen` via `LocalizedResources` (`solomapling.language: zh-CN`).

Only files that need translating live here; anything missing falls back to the English
`FMNameDesc/` list. Deliberately not translated:

| File | Why |
|------|-----|
| `randomRealMaplestoryIGNs.txt` | Bot IGNs stay ASCII — v83 character names are latin-only |
| `emojiFaces.txt` | Language-neutral kaomoji |

Keep entries short (roughly 4–10 characters). Two entries can be concatenated into one shop
title, and `FMClans.txt` names additionally get `[X]` emblem + ASCII borders, which throws
above 17 characters.
