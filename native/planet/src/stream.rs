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
        let dx = (self.max[0] - self.min[0] + 1).max(0) as u64;
        let dy = (self.max[1] - self.min[1] + 1).max(0) as u64;
        let dz = (self.max[2] - self.min[2] + 1).max(0) as u64;
        dx * dy * dz
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
