//! Streaming a local window.
//!
//! The engine can only hold a bounded box, so the mod never hands it the
//! planet. It hands it a window: a small range of *local* block coordinates
//! around the player. Each local block is mapped through the frame back into
//! planet space, where the radius may be millions of blocks and the shell may
//! be number 18. This is the whole trick that makes arbitrary-size planets
//! possible inside a 2032-block-tall level.

use crate::frame::LocalFrame;
use crate::planet::{Block, Planet};

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
