//! Seeded 3D value noise, sampled on the unit sphere.
//!
//! The article's point: a flat world uses a 2D height map, but a sphere has no
//! seamless 2D chart. Sampling 3D noise along the surface direction avoids
//! both the seam and the latitude distortion.

use crate::vec::Vec3;

fn hash3(x: i64, y: i64, z: i64, seed: u64) -> f64 {
    let mut h = seed
        .wrapping_add((x as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15))
        .wrapping_add((y as u64).wrapping_mul(0xC2B2_AE3D_27D4_EB4F))
        .wrapping_add((z as u64).wrapping_mul(0x1656_67B1_9E37_79F9));
    h ^= h >> 33;
    h = h.wrapping_mul(0xFF51_AFD7_ED55_8CCD);
    h ^= h >> 33;
    h = h.wrapping_mul(0xC4CE_B9FE_1A85_EC53);
    h ^= h >> 33;
    (h >> 11) as f64 / (1u64 << 53) as f64
}

fn lerp(a: f64, b: f64, t: f64) -> f64 {
    a + (b - a) * t
}

fn smooth(t: f64) -> f64 {
    t * t * (3.0 - 2.0 * t)
}

/// Value noise in `[-1, 1]`.
pub fn value_noise(p: Vec3, seed: u64) -> f64 {
    let f = p.floor();
    let (ix, iy, iz) = (f.x as i64, f.y as i64, f.z as i64);
    let tx = smooth(p.x - f.x);
    let ty = smooth(p.y - f.y);
    let tz = smooth(p.z - f.z);

    let c = |dx: i64, dy: i64, dz: i64| hash3(ix + dx, iy + dy, iz + dz, seed);

    let x00 = lerp(c(0, 0, 0), c(1, 0, 0), tx);
    let x10 = lerp(c(0, 1, 0), c(1, 1, 0), tx);
    let x01 = lerp(c(0, 0, 1), c(1, 0, 1), tx);
    let x11 = lerp(c(0, 1, 1), c(1, 1, 1), tx);

    let y0 = lerp(x00, x10, ty);
    let y1 = lerp(x01, x11, ty);
    lerp(y0, y1, tz) * 2.0 - 1.0
}

/// Fractal Brownian motion: octaves of value noise at doubling frequency and
/// halving amplitude, normalized to `[-1, 1]`.
pub fn fbm(p: Vec3, seed: u64, octaves: u32) -> f64 {
    let mut sum = 0.0;
    let mut amp = 1.0;
    let mut freq = 1.0;
    let mut total = 0.0;
    for o in 0..octaves {
        sum += value_noise(p * freq, seed.wrapping_add(o as u64 * 0x9E37_79B9)) * amp;
        total += amp;
        amp *= 0.5;
        freq *= 2.0;
    }
    if total == 0.0 {
        0.0
    } else {
        sum / total
    }
}
