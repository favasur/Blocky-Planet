//! Concentric shells: the trick that keeps blocks roughly cubic at any depth
//! and, crucially, without any maximum planet size.
//!
//! Shell `k` starts at radius `base_layers * (2^k - 1)` and is
//! `base_layers * 2^k` layers thick. Its grid has `base_resolution * 2^k`
//! blocks per sector side. Both the thickness and the resolution double, so a
//! block stays about as wide as it is tall at the bottom of every shell, while
//! the number of shells needed grows only logarithmically with the radius:
//! Earth-sized (r = 6,371,000) is shell 18, not shell 398,187.
//!
//! The exponential growth is computed in `u128` and `f64`, never in `int`, so
//! it does not overflow at planetary radii. That was the fatal bug in the
//! unfinished Java version.

use crate::vec::Vec3;

#[derive(Clone, Copy, Debug)]
pub struct ShellModel {
    /// Layers in shell 0, and the number of radial blocks the first shell spans.
    pub base_layers: u128,
    /// Blocks per sector side in shell 0.
    pub base_resolution: u128,
}

impl Default for ShellModel {
    fn default() -> Self {
        // 16 layers and 25 blocks per side keep shell 0's blocks nearly cubic
        // (25 / (pi/2 * 16) ~= 0.995) and match the 16x16x16 chunk size.
        Self {
            base_layers: 16,
            base_resolution: 25,
        }
    }
}

impl ShellModel {
    /// Radius where shell `k` begins.
    pub fn inner_radius(&self, k: u32) -> f64 {
        self.base_layers as f64 * (2f64.powi(k.min(60) as i32) - 1.0)
    }

    /// Layers (radial blocks) in shell `k`.
    pub fn layers(&self, k: u32) -> u128 {
        self.base_layers << k.min(60)
    }

    /// Blocks per sector side in shell `k`.
    pub fn resolution(&self, k: u32) -> u128 {
        self.base_resolution << k.min(60)
    }

    /// Radius where shell `k` ends.
    pub fn outer_radius(&self, k: u32) -> f64 {
        self.inner_radius(k) + self.layers(k) as f64
    }

    /// Shell index containing radius `r`. Shells are logarithmic, so this is a
    /// short loop (about 19 steps for an Earth-sized planet) and never divides
    /// by a fixed height.
    pub fn shell_of(&self, r: f64) -> u32 {
        let mut k = 0u32;
        while k < 60 && self.inner_radius(k + 1) <= r {
            k += 1;
        }
        k
    }

    /// Shell index and layer within it for radius `r`.
    pub fn locate(&self, r: f64) -> (u32, u128) {
        let k = self.shell_of(r);
        let layer = (r - self.inner_radius(k)).floor().max(0.0) as u128;
        (k, layer.min(self.layers(k).saturating_sub(1)))
    }

    /// Chunks along one horizontal side of a shell (the surface grid is
    /// square on every face).
    pub fn chunks_per_side(&self, k: u32) -> u128 {
        self.resolution(k).div_ceil(crate::CHUNK_SIZE)
    }

    /// Chunks along the radial axis of a shell.
    pub fn chunks_radial(&self, k: u32) -> u128 {
        self.layers(k).div_ceil(crate::CHUNK_SIZE)
    }

    /// How many shells a planet of `radius` actually uses.
    pub fn shell_count(&self, radius: f64) -> u32 {
        self.shell_of(radius) + 1
    }

    /// Planet-space position of a block given its shell, layer and face
    /// coordinates, at the block's centre.
    pub fn block_center(
        &self,
        sector: crate::sector::Sector,
        k: u32,
        i: u128,
        j: u128,
        layer: u128,
    ) -> Vec3 {
        let n = self.resolution(k) as f64;
        let u = (i as f64 + 0.5) / n * 2.0 - 1.0;
        let v = (j as f64 + 0.5) / n * 2.0 - 1.0;
        let r = self.inner_radius(k) + layer as f64 + 0.5;
        sector.position(u, v, r)
    }
}
