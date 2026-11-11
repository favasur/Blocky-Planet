// Engine-side writer: runs on the authoritative server's player tick, asks
// `EngineWriter` for the blocks that entered the player's window, and sends
// them to the loaded chunks as one `native_edit_blocks` bulk edit.
//
// The native mod API used here:
//   * `native_acting_player()` (API 21) gives the player whose tick this is,
//     with UUID, dimension and feet position.
//   * `native_edit_blocks(dimension, &NativeBlockEdit)` (API 126) writes runs
//     straight into loaded chunks and sends the changed chunks to players.
//
// `EngineWriter` owns the floating frame and the spherical `ChunkStream`; this
// file only converts its `WriteBatch` to block-state names and column runs.

use crate::game::native_world_edit::{NativeBlockEdit, NativeBlockRun};
use blocky_planet_core::{Block, EngineWriter, LocalFrame, Planet, Vec3, WriteBatch};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// The engine-local window materialized around each player. These are blocks,
/// not chunks: 33 x 65 x 33 around the feet, far below the 2032-block level
/// ceiling and independent of the planet's radius.
const VIEW_RADIUS: i32 = 16;
const VIEW_DOWN: i32 = 48;
const VIEW_UP: i32 = 16;
const HORIZONTAL_CHUNKS: u128 = 1;
const VERTICAL_CHUNKS: u128 = 2;

struct Entry {
    writer: EngineWriter,
    dimension: String,
    revision: u64,
}

fn writers() -> &'static Mutex<HashMap<u128, Entry>> {
    static WRITERS: OnceLock<Mutex<HashMap<u128, Entry>>> = OnceLock::new();
    WRITERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn new_entry(planet: &Planet, engine: Vec3, dimension: String, revision: u64) -> Entry {
    let surface = Vec3::X * planet.surface_radius_at(Vec3::X);
    let frame = LocalFrame::anchored(surface, engine);
    Entry {
        writer: EngineWriter::new(
            frame,
            surface,
            planet,
            HORIZONTAL_CHUNKS,
            VERTICAL_CHUNKS,
            VIEW_RADIUS,
            VIEW_DOWN,
            VIEW_UP,
        ),
        dimension,
        revision,
    }
}

fn state_name(block: Block) -> &'static str {
    match block {
        Block::Air => "minecraft:air",
        Block::Water => "minecraft:water",
        Block::Grass => "minecraft:grass_block",
        Block::Dirt => "minecraft:dirt",
        Block::Stone => "minecraft:stone",
        Block::Deepslate => "minecraft:deepslate",
        Block::Bedrock => "minecraft:bedrock",
        Block::Netherrack => "minecraft:netherrack",
        Block::Lava => "minecraft:lava",
    }
}

fn palette_index(palette: &mut Vec<String>, name: &str) -> u32 {
    if let Some(index) = palette.iter().position(|entry| entry == name) {
        return index as u32;
    }
    palette.push(name.to_owned());
    (palette.len() - 1) as u32
}

/// Turn block positions into vertical runs. A column of stone becomes one run
/// of height N instead of N separate runs, which is what `native_edit_blocks`
/// is built for.
fn push_runs(
    items: &mut [([i32; 3], Block)],
    palette: &mut Vec<String>,
    runs: &mut Vec<NativeBlockRun>,
) {
    items.sort_by_key(|(pos, _)| (pos[0], pos[2], pos[1]));
    let mut i = 0usize;
    while i < items.len() {
        let (pos, block) = items[i];
        let mut height = 1u32;
        while (i + height as usize) < items.len() {
            let (next, next_block) = items[i + height as usize];
            if next[0] == pos[0]
                && next[2] == pos[2]
                && next[1] == pos[1] + height as i32
                && next_block == block
            {
                height += 1;
            } else {
                break;
            }
        }
        runs.push(NativeBlockRun {
            x: pos[0],
            z: pos[2],
            y: pos[1],
            height,
            surface: None,
            block: palette_index(palette, state_name(block)),
            only: None,
        });
        i += height as usize;
    }
}

fn edit_for(batch: &WriteBatch) -> NativeBlockEdit {
    let mut palette = Vec::new();
    let mut runs = Vec::new();

    // Clear first, then place, so a rebase that reuses a local position is not
    // left as air. Both lists are grouped independently.
    let mut clear: Vec<([i32; 3], Block)> = batch
        .clear
        .iter()
        .map(|pos| (*pos, Block::Air))
        .collect();
    push_runs(&mut clear, &mut palette, &mut runs);

    let mut set: Vec<([i32; 3], Block)> = batch
        .set
        .iter()
        .map(|place| (place.pos, place.block))
        .collect();
    push_runs(&mut set, &mut palette, &mut runs);

    NativeBlockEdit {
        palette,
        runs,
        ..Default::default()
    }
}

/// Injected at the top of `native_player_tick_pre`, so it runs once per living
/// player, with that player's state swapped in. It does nothing on a client
/// (there is no authoritative server) and nothing when the window is unchanged.
fn blocky_planet_stream(game: &mut crate::game::Game<'_>) {
    use crate::game::blocky_planet as m;

    if !game.native_on(m::ID) {
        return;
    }
    let Some(player) = game.native_acting_player() else {
        return;
    };
    let engine = Vec3::new(player.position[0], player.position[1], player.position[2]);
    if !engine.is_finite() {
        return;
    }

    let planet = m::planet();
    let revision = m::revision();
    let (batch, dimension) = {
        let mut map = writers()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let entry = map.entry(player.uuid).or_insert_with(|| {
            new_entry(&planet, engine, player.dimension.clone(), revision)
        });
        if entry.revision != revision || entry.dimension != player.dimension {
            *entry = new_entry(
                &planet,
                engine,
                player.dimension.clone(),
                revision,
            );
        }
        let player_planet = entry.writer.frame.to_planet(engine);
        (
            entry.writer.update(&planet, engine, player_planet),
            player.dimension.clone(),
        )
    };

    if batch.set.is_empty() && batch.clear.is_empty() {
        return;
    }
    let edit = edit_for(&batch);
    let _ = game.native_edit_blocks(&dimension, &edit);
}
