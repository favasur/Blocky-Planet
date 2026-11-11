//! Streaming a local window.
//!
//! The engine can only hold a bounded box, so the mod never hands it the
//! planet. It hands it a window: a small range of *local* block coordinates
//! around the player. Each local block is mapped through the frame back into
//! planet space, where the radius may be millions of blocks and the shell may
//! be number 18. This is the whole trick that makes arbitrary-size planets
//! possible inside a 2032-block-tall level.

use crate::address::{BlockAddress, ShellChunk};
use crate::frame::LocalFrame;
use crate::planet::{Block, Planet};
use crate::shell::ShellModel;
use crate::vec::Vec3;
use std::collections::HashSet;

/// Hard ceiling on a window the game may ask for. Four million block
/// positions is a 512 x 512 footprint 16 blocks deep, or 128 x 128 x 256.
/// Anything larger is a bug in the caller (usually a full radial column), not
/// a render distance.
pub const MAX_WINDOW_BLOCKS: u64 = 4_194_304;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WindowError {
    TooLarge { blocks: u64, max: u64 },
    Inverted,
}

#[derive(Clone, Copy, Debug)]
pub struct LocalWindow {
    pub min: [i32; 3],
    pub max: [i32; 3],
}

impl LocalWindow {
    pub fn new(min: [i32; 3], max: [i32; 3]) -> Self {
        Self { min, max }
    }

    pub fn volume(&self) -> u64 {
        let dx = (self.max[0] as i64 - self.min[0] as i64 + 1).max(0) as u64;
        let dy = (self.max[1] as i64 - self.min[1] as i64 + 1).max(0) as u64;
        let dz = (self.max[2] as i64 - self.min[2] as i64 + 1).max(0) as u64;
        dx.saturating_mul(dy).saturating_mul(dz)
    }

    /// Reject a window that would make the game iterate millions of blocks.
    pub fn checked(self) -> Result<Self, WindowError> {
        if self.max[0] < self.min[0] || self.max[1] < self.min[1] || self.max[2] < self.min[2] {
            return Err(WindowError::Inverted);
        }
        let blocks = self.volume();
        if blocks > MAX_WINDOW_BLOCKS {
            return Err(WindowError::TooLarge {
                blocks,
                max: MAX_WINDOW_BLOCKS,
            });
        }
        Ok(self)
    }

    /// Call `f` for every block in the window, with its local position and the
    /// planet block there.
    pub fn for_each(
        &self,
        planet: &Planet,
        frame: &LocalFrame,
        mut f: impl FnMut([i32; 3], Block),
    ) {
        for y in self.min[1]..=self.max[1] {
            for z in self.min[2]..=self.max[2] {
                for x in self.min[0]..=self.max[0] {
                    let p = frame.to_planet_block([x, y, z]);
                    f([x, y, z], planet.block_at(p));
                }
            }
        }
    }

    /// Number of solid and total blocks: a cheap sanity probe for a command.
    pub fn count(&self, planet: &Planet, frame: &LocalFrame) -> (u64, u64) {
        let mut solid = 0;
        self.for_each(planet, frame, |_, b| {
            if b.is_solid() {
                solid += 1;
            }
        });
        (solid, self.volume())
    }
}

/// The bounded block window the engine materializes around the player. It is
/// deliberately **not** a column: `down` and `up` are constants, so the cost of
/// a window is independent of the planet's radius. When the player descends,
/// the window moves down and the layer profile answers the new radii.
pub struct GenerationWindow {
    pub frame: LocalFrame,
    pub window: LocalWindow,
}

impl GenerationWindow {
    pub fn around(feet: Vec3, radius: i32, down: i32, up: i32) -> Result<Self, WindowError> {
        if radius < 0 || down < 0 || up < 0 {
            return Err(WindowError::Inverted);
        }
        let window = LocalWindow::new([-radius, -down, -radius], [radius, up, radius]).checked()?;
        Ok(Self {
            frame: LocalFrame::at(feet),
            window,
        })
    }

    pub fn block_at(&self, planet: &Planet, local: [i32; 3]) -> Block {
        planet.block_at(self.frame.to_planet_block(local))
    }
}

/// Tracks the spherical 16x16x16 chunks around a moving player.
///
/// The engine stores local column chunks, but the planet's unit is a
/// `ShellChunk` in shell space. This streamer owns the planet-space set; after
/// a horizontal or vertical move, [`ChunkStream::update`] returns only the
/// chunks that entered the window, so those are the chunks to generate. A
/// chunk that leaves is dropped; the planet itself is unchanged.
pub struct ChunkStream {
    pub shells: ShellModel,
    pub horizontal: u128,
    pub vertical: u128,
    loaded: HashSet<ShellChunk>,
}

impl ChunkStream {
    pub fn new(shells: ShellModel, player: Vec3, horizontal: u128, vertical: u128) -> Self {
        let mut stream = Self {
            shells,
            horizontal,
            vertical,
            loaded: HashSet::new(),
        };
        stream.loaded = stream.around(player);
        stream
    }

    pub fn loaded(&self) -> usize {
        self.loaded.len()
    }

    /// Move the player and return the chunks that are newly in range.
    pub fn update(&mut self, player: Vec3) -> Vec<ShellChunk> {
        let needed = self.around(player);
        let mut added: Vec<ShellChunk> = needed.difference(&self.loaded).copied().collect();
        added.sort_by_key(|c| (c.shell, c.layer, c.i, c.j));
        self.loaded = needed;
        added
    }

    /// Sample one chunk through the planet's layer profile, for the chunk
    /// writer. The callback receives each block's address and block.
    pub fn for_each_block(
        &self,
        planet: &Planet,
        chunk: ShellChunk,
        mut f: impl FnMut(BlockAddress, Block),
    ) {
        for di in 0..crate::CHUNK_SIZE {
            for dl in 0..crate::CHUNK_SIZE {
                for dj in 0..crate::CHUNK_SIZE {
                    let a = chunk.address([di, dl, dj]);
                    f(a, planet.block_at(a.center(&self.shells)));
                }
            }
        }
    }

    fn around(&self, player: Vec3) -> HashSet<ShellChunk> {
        let a = BlockAddress::from_position(&self.shells, player);
        let (ci, cl, cj, _) = a.chunk();
        let chunks_per_side = self.shells.chunks_per_side(a.shell) as i128;
        let chunks_radial = self.shells.chunks_radial(a.shell) as i128;
        let mut out = HashSet::new();

        for di in -(self.horizontal as i128)..=(self.horizontal as i128) {
            let i = ci as i128 + di;
            if i < 0 || i >= chunks_per_side {
                continue;
            }
            for dj in -(self.horizontal as i128)..=(self.horizontal as i128) {
                let j = cj as i128 + dj;
                if j < 0 || j >= chunks_per_side {
                    continue;
                }
                for dl in -(self.vertical as i128)..=(self.vertical as i128) {
                    let layer = cl as i128 + dl;
                    if layer < 0 || layer >= chunks_radial {
                        continue;
                    }
                    out.insert(ShellChunk {
                        sector: a.sector,
                        shell: a.shell,
                        i: i as u128,
                        layer: layer as u128,
                        j: j as u128,
                    });
                }
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const EARTH: u64 = 6_371_000;

    #[test]
    fn a_generation_window_is_a_vertical_band_not_a_column() {
        let planet = Planet::new(EARTH, 1);
        let surface = planet.surface_radius_at(Vec3::X);
        let feet = Vec3::X * (surface + 0.5);
        let view = GenerationWindow::around(feet, 8, 256, 64).unwrap();
        assert_eq!(view.window.volume(), 17 * 321 * 17);
        let (solid, total) = view.window.count(&planet, &view.frame);
        assert!(solid > 0 && solid < total);

        // Moving down moves the band with the player. The block at its centre
        // is mantle, because the layer profile is solid all the way down.
        let deep = Vec3::X * (surface - 30_000.0);
        let below = GenerationWindow::around(deep, 8, 128, 16).unwrap();
        assert_eq!(below.block_at(&planet, [0, 0, 0]), Block::Deepslate);

        // Moving horizontally keeps the same bounded cost.
        let east = LocalFrame::at(feet).east;
        let moved = GenerationWindow::around(feet + east * 32.0, 8, 256, 64).unwrap();
        assert_eq!(moved.window.volume(), view.window.volume());
        assert!(moved.block_at(&planet, [0, -1, 0]).is_solid());
    }

    #[test]
    fn moving_the_player_adds_only_the_chunks_that_enter_the_window() {
        let planet = Planet::new(EARTH, 1);
        let p = Vec3::X * planet.surface_radius_at(Vec3::X);
        let mut stream = ChunkStream::new(planet.shells, p, 2, 1);
        let initial = stream.loaded();
        assert_eq!(initial, 75);

        let east = LocalFrame::at(p).east;
        let added = stream.update(p + east * 32.0);
        assert!(!added.is_empty(), "a horizontal move added no chunks");
        assert_eq!(stream.loaded(), initial);

        // A vertical move brings deeper shell chunks into range; they sample
        // the mantle, not a void band.
        let deep = p - Vec3::X * 30_000.0;
        let added = stream.update(deep);
        assert!(!added.is_empty(), "a vertical move added no chunks");
        let chunk = added
            .iter()
            .find(|c| c.shell == planet.shells.shell_of(deep.length()))
            .expect("no chunk in the player's shell");
        let block = planet.block_at(chunk.address([8, 8, 8]).center(&planet.shells));
        assert_eq!(block, Block::Deepslate);
        assert_eq!(stream.loaded(), initial);
    }
}
