# Shardbound

Shardbound is a top-down Java party RPG with:

- up to 4 class slots (Mage, Warrior, Tank, Priest)
- Tiled JSON maps with collision and portals
- save/load quickstates
- LAN/TCP host-client networking
- elemental skill/status system
- map-aware enemies with 10-tile sensing and pathfinding

## Running

Build and run with your normal Java/Maven workflow.

Network mode is configured with JVM args:

- local (default): `--mode=local`
- LAN host: `--mode=lan-host --port=7777`
- LAN client: `--mode=lan-client --host=HOST_IP --port=7777`
- TCP host: `--mode=tcp-host --port=7777`
- TCP client: `--mode=tcp-client --host=HOST_IP --port=7777`

Networking model:

- host is authoritative
- clients send inputs
- host publishes snapshots

## Controls

Join slots:

- `F1` Mage
- `F2` Warrior
- `F3` Tank
- `F4` Priest

Per-slot controls:

- Mage: move `W A S D`, skills `1 2 3 4`, item modifier `Shift`
- Warrior: move arrows, skills `NumPad1-4`, item modifier `Enter`
- Tank: move `I J K L`, skills `Z X C V`, item modifier `O`
- Priest: move `T F G H`, skills `R U B N`, item modifier `Y`

Save state:

- save quickstate: `F5`
- load quickstate: `F9`
- file: `saves/quicksave.properties`

## Classes, Elements, and Items

Class defaults:

- Mage -> `FIRE`
- Warrior -> `LIGHTNING`
- Tank -> `EARTH`
- Priest -> `ICE`

Element/status behavior:

- active skill type is the current signature element (`FIRE`, `ICE`, `LIGHTNING`, `EARTH`)
- applied status effect is derived from the active element
- all classes can cycle elements at runtime

Inventory item controls:

- `modifier + skill1`: cycle selected item
- `modifier + skill2`: use selected item
- `modifier + skill3`: increase selected attribute
- `modifier + skill4`: decrease selected attribute

Items:

- `ELEMENT_TUNER`: cycles active element
- `STATUS_TUNER`: cycles status (and syncs matching element)
- `ATTRIBUTE_TUNER`: cycles editable attribute

Editable attributes:

- `MAX_HP`
- `HP_REGEN`
- `MAX_MANA`
- `MANA_REGEN`
- `AP`
- `DEFENCE`

Leveling:

- starting level is `0`
- max level is `128`

## Map Authoring (Tiled)

Maps are loaded from classpath resources at `maps/<id>.json`.
Current default IDs are: `world`, `cave`, `dungeon`.

Collision:

- tile layer name `collision`, or layer property `collidable=true`
- solid tiles are marked in tileset tile properties with `solid=true`

Portals:

- object layer name `portals`
- each rectangle object supports:
- `targetMap` (destination map id)
- `targetX` (destination x in pixels)
- `targetY` (destination y in pixels)

Friendly fire zones:

- friendly fire is OFF by default
- enable in specific areas using object layer `friendly_fire` (or `pvp`)
- add rectangle objects to define PvP-active regions

Enemy variants:

- enemies sense players from up to 10 tiles away
- enemies spawn from walkable tile variants
- each enemy is locked to its spawn variant
- enemies pathfind only through tiles of their own variant
