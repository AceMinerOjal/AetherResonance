# Shardbound

Shardbound is a top-down party RPG in Java with:

- up to 4 players (one per class),
- Tiled JSON world maps with portals,
- local play and network play (LAN + TCP),
- quicksave support.

## Maps (Tiled)

Expected map files in `res/maps/`:

- `world.json`
- `cave.json`
- `dungeon.json`

### Collision

- Use a tile layer named `collision`, or set layer property `collidable=true`.
- Set tile property `solid=true` on blocking tiles in the tileset.

### Portals

- Use an object layer named `portals`.
- Create rectangle objects and add `targetMap`, `targetX`, and `targetY` properties.
- `targetMap`: destination map id (`world`, `cave`, `dungeon`, or any registered id).
- `targetX`: destination x in pixels.
- `targetY`: destination y in pixels.

## Controls

Join class slots:

- `F1` Mage
- `F2` Warrior
- `F3` Tank
- `F4` Priest

Per-class controls:

- Mage: move `WASD`, skills `1 2 3 4`, modifier `Shift`
- Warrior: move arrow keys, skills numpad `1 2 3 4`, modifier `Enter`
- Tank: move `I J K L`, skills `Z X C V`, modifier `O`
- Priest: move `T F G H`, skills `R U B N`, modifier `Y`

### Signature elements

- Every class has a default signature element:
- Mage = `FIRE`
- Warrior = `LIGHTNING`
- Tank = `EARTH`
- Priest = `ICE`
- All classes can use all status effects.
- Status effects are switched by using an inventory item (placeholder inventory system).
- Item controls:
- `modifier + skill1`: switch selected inventory item
- `modifier + skill2`: use selected inventory item
- Inventory items:
- `STATUS_TUNER`: cycles status effects (`BURN`, `FREEZE`, `CONDUCTIVE`, `FRACTURE`)
- `ELEMENT_TUNER`: cycles signature element (`FIRE`, `ICE`, `LIGHTNING`, `EARTH`)
- `ATTRIBUTE_TUNER`: cycles editable attribute (`MAX_HP`, `HP_REGEN`, `MAX_MANA`, `MANA_REGEN`, `AP`, `DEFENCE`)
- Offensive skills apply whichever status effect is currently selected.

### Runtime attribute editing

- Any class can modify its attributes at runtime via item inputs:
- `modifier + skill3`: increase selected attribute
- `modifier + skill4`: decrease selected attribute
- Selected attribute is cycled by using `ATTRIBUTE_TUNER`.
- Editable attributes: `MAX_HP`, `HP_REGEN`, `MAX_MANA`, `MANA_REGEN`, `AP`, `DEFENCE`

## Save States

- Save quickstate: `F5`
- Load quickstate: `F9`
- File path: `saves/quicksave.properties`

## Networking

Run with JVM args:

- Local (default): `--mode=local`
- LAN host: `--mode=lan-host --port=7777`
- LAN client: `--mode=lan-client --host=HOST_IP --port=7777`
- TCP host: `--mode=tcp-host --port=7777`
- TCP client: `--mode=tcp-client --host=HOST_IP --port=7777`

Notes:

- Host is authoritative.
- Clients send input; host sends snapshots.
- Slot/class mapping is fixed:
- Slot 0 Mage (host local)
- Slot 1 Warrior
- Slot 2 Tank
- Slot 3 Priest
