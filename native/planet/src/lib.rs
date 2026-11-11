//! Blocky Planet core: an **unbounded** spherical voxel planet.
//!
//! Horizon (and Minecraft's model in general) caps a level's height at 2032
//! blocks: `BlockPos` packs Y in 12 bits and every column is a fixed stack of
//! sections. A planet of arbitrary size therefore cannot live inside one level.
//! This crate keeps the planet in 64-bit planet space instead, and exposes the
//! small, gravity-aligned *local frame* that the engine actually renders.
//!
//! The model follows <https://bowerbyte.com/posts/blocky-planet/>:
//!
//! * six cube-face **sectors** (a quad sphere), so there are no poles;
//! * concentric **shells** that double their layer count and per-side
//!   resolution as they move outward, keeping blocks roughly cubic at every
//!   depth while letting the planet grow without any hard limit;
//! * a **block address** of sector -> shell -> chunk -> block;
//! * terrain sampled from 3D noise on the unit sphere (no seams, no
//!   latitude/longitude distortion).
//!
//! Everything here is plain `f64`/integer math, so it unit-tests on the host
//! with `cargo test` and compiles into the game unchanged.

pub mod address;
pub mod frame;
pub mod lod;
pub mod noise;
pub mod planet;
pub mod sector;
pub mod shape;
pub mod shell;
pub mod stream;
pub mod vec;

pub use address::{AddressSpace, BlockAddress, CoreAddress, ShellChunk};
pub use frame::LocalFrame;
pub use lod::{horizon_distance, surface_drop, Atmosphere, LodPlan, LodRing};
pub use planet::{Block, Planet};
pub use sector::{Sector, ALL_SECTORS};
pub use shape::{BlockGeometry, BlockShape};
pub use shell::ShellModel;
pub use stream::LocalWindow;
pub use vec::Vec3;

/// Chunks are 16x16x16 blocks, as in the article, so chunk work is uniform
/// wherever on (or in) the planet it happens.
pub const CHUNK_SIZE: u128 = 16;

#[cfg(test)]
mod tests {
    use super::*;

    const EARTH: u64 = 6_371_000;

    #[test]
    fn sectors_have_a_right_handed_basis() {
        for s in ALL_SECTORS {
            let (n, u, v) = (s.normal(), s.u_axis(), s.v_axis());
            assert!((n.length() - 1.0).abs() < 1e-12);
            assert!((u.length() - 1.0).abs() < 1e-12);
            assert!((v.length() - 1.0).abs() < 1e-12);
            assert!(n.dot(u).abs() < 1e-12);
            assert!(n.dot(v).abs() < 1e-12);
            assert!(u.dot(v).abs() < 1e-12);
            let handed = u.cross(v);
            assert!((handed - n).length() < 1e-12, "{} is left-handed", s.name());
        }
    }

    #[test]
    fn cube_face_coordinates_round_trip_through_the_sphere() {
        for s in ALL_SECTORS {
            for (u, v) in [(-0.4, 0.7), (0.25, -0.5), (0.9, 0.1)] {
                let p = s.position(u, v, 1234.5);
                let (s2, u2, v2) = Sector::from_planet(p);
                assert_eq!(s, s2);
                assert!((u - u2).abs() < 1e-9);
                assert!((v - v2).abs() < 1e-9);
            }
        }
    }

    #[test]
    fn the_surface_is_covered_by_the_expected_number_of_shells() {
        let shells = ShellModel::default();
        // Earth-sized planets need ~19 shells, not 398,187: thickness and
        // resolution double together, so the index grows logarithmically.
        assert_eq!(shells.shell_of(EARTH as f64), 18);
        assert_eq!(Planet::new(EARTH, 0).shell_count(), 19);
        assert_eq!(shells.shell_of(1_000.0), 5);
    }

    #[test]
    fn shells_keep_blocks_cubic_at_every_depth() {
        let shells = ShellModel::default();
        for k in 3..=12u32 {
            let inner = shells.inner_radius(k);
            let width = (std::f64::consts::FRAC_PI_2 * inner) / shells.resolution(k) as f64;
            // One layer is one block tall; the width should be near it.
            assert!(
                (0.8..=1.3).contains(&width),
                "shell {k}: width {width} for one-layer height"
            );
        }
    }

    #[test]
    fn arbitrary_radius_never_overflows_or_panics() {
        for radius in [500u64, 12_742_000, 1_000_000_000, 9_000_000_000_000_000_000] {
            let planet = Planet::new(radius, 7);
            let r = radius as f64;
            let p = Vec3::new(r, 0.0, 0.0);
            let address = planet.address_of(p);
            assert!(address.shell < 60, "shell {} at r {radius}", address.shell);
            assert_eq!(address.sector, Sector::PosX);
            // Nothing panics at the outer edge, into space, or at the core.
            assert_eq!(planet.block_at(p * 1.5), Block::Air);
            assert_eq!(planet.block_at(Vec3::ZERO), Block::Air);
            let surface = planet.surface_radius_at(Vec3::X);
            assert!(planet.block_at(Vec3::X * (surface - 1.0)).is_solid());
            // Below the crust is void, not millions of deepslate blocks.
            // (Beyond 2^53 a 300-block subtraction no longer changes the f64.)
            if surface - 300.0 != surface {
                assert_eq!(planet.block_at(Vec3::X * (surface - 300.0)), Block::Air);
            }
        }
    }

    #[test]
    fn block_addresses_round_trip_at_planetary_scale() {
        let shells = ShellModel::default();
        let r = EARTH as f64;
        for s in ALL_SECTORS {
            for (u, v) in [(-0.3, 0.2), (0.55, -0.75), (0.05, 0.05)] {
                for offset in [-120.0, 0.0, 400.0] {
                    let p = s.position(u, v, r + offset);
                    let a = BlockAddress::from_position(&shells, p);
                    let back = BlockAddress::from_position(&shells, a.center(&shells));
                    assert_eq!(a.sector, back.sector);
                    assert_eq!(a.shell, back.shell);
                    assert!(
                        a.i.abs_diff(back.i) <= 1
                            && a.j.abs_diff(back.j) <= 1
                            && a.layer.abs_diff(back.layer) <= 1,
                        "{a:?} -> {back:?}"
                    );
                }
            }
        }
    }

    #[test]
    fn a_seam_maps_to_the_neighbouring_sector() {
        // Just past the +X/+Z edge, the dominant axis changes and so does the
        // face: this is the seam the article's index swap/flip handles.
        let a = Sector::from_planet(Vec3::new(100.0, 0.0, 100.001));
        let b = Sector::from_planet(Vec3::new(100.001, 0.0, 100.0));
        assert_eq!(a.0, Sector::PosZ);
        assert_eq!(b.0, Sector::PosX);
        assert!(a.1 > 0.99);
        // The neighbouring face's u axis runs the other way: that flip is the
        // "axes are flipped" case the article's seam table handles.
        assert!(b.1 < -0.99);
    }

    #[test]
    fn terrain_is_deterministic_and_layered() {
        let planet = Planet::new(EARTH, 42);
        let dir = Vec3::X;
        let p = dir * planet.surface_radius_at(dir);
        let surface = planet.surface_radius_at(dir);

        assert_eq!(planet.block_at(p), Block::Grass);
        assert_eq!(planet.block_at(dir * (surface - 2.0)), Block::Dirt);
        assert_eq!(planet.block_at(dir * (surface - 40.0)), Block::Stone);
        assert_eq!(planet.block_at(dir * (surface - 100.0)), Block::Deepslate);
        assert_eq!(planet.block_at(dir * (surface - 300.0)), Block::Air);
        assert_eq!(
            planet.block_at(Vec3::new(1.0, 2.0, 3.0) * 10.0),
            planet.block_at(Vec3::new(1.0, 2.0, 3.0) * 10.0)
        );
    }

    #[test]
    fn the_core_is_hollow_with_a_bedrock_shell() {
        let planet = Planet::new(EARTH, 42);
        assert_eq!(planet.block_at(Vec3::ZERO), Block::Air);
        assert_eq!(
            planet.block_at(Vec3::X * (planet.radius * 0.60 - 2.0)),
            Block::Bedrock
        );
        assert_eq!(
            planet.block_at(Vec3::X * (planet.radius * 0.50)),
            Block::Air
        );
    }

    #[test]
    fn the_local_frame_round_trips_and_stays_small() {
        let planet = Planet::new(EARTH, 1);
        let feet = Vec3::new(planet.radius + 1.0, 0.0, 0.0);
        let frame = LocalFrame::at(feet);

        // Local up is radially outward, and local gravity is -Y.
        assert!((frame.up - Vec3::X).length() < 1e-9);
        assert_eq!(frame.local_gravity(), Vec3::new(0.0, -1.0, 0.0));
        assert!((frame.east.dot(frame.up)).abs() < 1e-12);
        assert!((frame.north.dot(frame.up)).abs() < 1e-12);

        // A 32-block local offset is a 32-block planet offset: the planet is
        // millions of blocks away, the engine only ever sees the offset.
        let planet_pos = frame.to_planet_block([0, 8, 0]);
        assert!(((planet_pos - feet).length() - 8.0).abs() < 1e-6);
        let back = frame.to_local(planet_pos);
        assert!((back - Vec3::new(0.0, 8.0, 0.0)).length() < 1e-6);

        // The window the renderer gets is tiny; the planet coordinate is not.
        assert!(feet.length() > 6.0e6);
        assert!(back.length() < 10.0);
    }

    #[test]
    fn a_window_on_the_surface_has_solid_ground_below_and_air_above() {
        let planet = Planet::new(EARTH, 3);
        let surface = planet.surface_radius_at(Vec3::X);
        let feet = Vec3::X * (surface + 0.5);
        let frame = LocalFrame::at(feet);
        let window = LocalWindow::new([-4, -24, -4], [4, 4, 4]);
        let _ = window;
        let (solid, total) = window.count(&planet, &frame);
        assert_eq!(total, 9 * 29 * 9);
        assert!(solid > 0, "no ground in the window");
        assert!(solid < total, "the whole window is solid: no sky");

        // Directly below the player must be ground; 20 blocks up must be air.
        let below = frame.to_planet_block([0, -8, 0]);
        let above = frame.to_planet_block([0, 20, 0]);
        assert!(planet.block_at(below).is_solid());
        assert!(!planet.block_at(above).is_solid());
    }

    #[test]
    fn gravity_falls_to_zero_at_the_centre() {
        let planet = Planet::new(EARTH, 1);
        let g = 9.8;
        assert!((planet.gravity_at(planet.radius * 2.0, g) - g).abs() < 1e-9);
        assert!((planet.gravity_at(planet.radius * 0.5, g) - g * 0.5).abs() < 1e-9);
        assert_eq!(planet.gravity_at(0.0, g), 0.0);
        assert!((planet.gravity_direction(Vec3::Y) - (-Vec3::Y)).length() < 1e-12);
    }

    #[test]
    fn shell_blocks_are_valid_frustums_at_every_depth() {
        for radius in [64u64, 500, EARTH] {
            let planet = Planet::new(radius, 5);
            for s in ALL_SECTORS {
                for (u, v) in [(-0.7, 0.2), (0.1, 0.8), (0.45, -0.3), (0.97, -0.94)] {
                    let dir = s.position(u, v, 1.0);
                    let surface = planet.surface_radius_at(dir);
                    for depth in [1.0, 4.0, 40.0, 100.0, 126.0] {
                        let p = dir * (surface - depth);
                        if planet.block_at(p).is_solid() {
                            let g = planet
                                .geometry_at(p)
                                .unwrap_or_else(|| panic!("no geometry at r={}", p.length()));
                            assert!(g.is_valid(), "invalid shape at r={}: {:?}", p.length(), g);
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn a_collapsed_central_frustum_is_rejected_not_returned() {
        let shells = ShellModel::default();
        // Shell 0 has cells meeting at radius zero: every wedge is degenerate.
        let central = BlockAddress {
            sector: Sector::PosX,
            shell: 0,
            i: 12,
            j: 12,
            layer: 0,
        };
        assert!(!BlockShape::from_address(&shells, &central).is_valid());

        let planet = Planet::new(64, 1);
        let inside_core = planet.address_space_of(Vec3::X * 8.0);
        assert!(inside_core.is_core());
        assert!(matches!(
            planet.address_space_of(Vec3::X * 20.0),
            AddressSpace::Shell(_)
        ));
    }

    #[test]
    fn tiny_planets_keep_their_surface_above_their_centre() {
        // Fixed +-64-block amplitude used to put a 64-block planet's "surface"
        // below r = 0, which then produced nonsense depths.
        let planet = Planet::new(64, 9);
        for s in ALL_SECTORS {
            let dir = s.position(0.3, -0.2, 1.0);
            assert!(planet.surface_radius_at(dir) > planet.radius * 0.9);
            assert!(planet.surface_radius_at(dir) < planet.radius * 1.1);
        }
        assert!(planet.block_at(Vec3::X * 60.0).is_solid());
    }

    #[test]
    fn a_column_is_a_few_hundred_solid_blocks_not_millions() {
        for radius in [500u64, EARTH, 9_000_000_000_000_000_000] {
            let planet = Planet::new(radius, 2);
            for s in ALL_SECTORS {
                let plan = planet.column_plan(s.position(0.2, -0.3, 1.0));
                let thickness = plan.solid_thickness();
                assert!(thickness <= 400.0, "{thickness} solid blocks at r={radius}");
                assert!(thickness.is_finite());
                // The full radius is orders of magnitude larger; generation must
                // never walk it block by block.
                if planet.radius > 10_000.0 {
                    assert!(planet.radius / thickness > 10_000.0);
                }
                let solid = plan
                    .solid_spans()
                    .iter()
                    .all(|(a, b)| a.is_finite() && b.is_finite() && b >= a);
                assert!(solid);
            }
        }
    }

    #[test]
    fn a_column_plan_matches_per_block_lookup() {
        let planet = Planet::new(EARTH, 11);
        for s in ALL_SECTORS {
            for (u, v) in [(0.1, 0.4), (-0.6, 0.8)] {
                let dir = s.position(u, v, 1.0);
                let plan = planet.column_plan(dir);
                for depth in [0.5, 2.0, 20.0, 120.0, 200.0] {
                    let r = plan.surface - depth;
                    assert_eq!(planet.block_at(dir * r), plan.block_at(planet.seed, dir, r));
                }
            }
        }
    }

    #[test]
    fn a_window_larger_than_the_budget_is_refused() {
        let huge = LocalWindow::new([-1000, -1_000_000, -1000], [1000, 1_000_000, 1000]);
        assert!(huge.checked().is_err());
        let normal = LocalWindow::new([-8, -16, -8], [8, 8, 8]);
        assert!(normal.checked().is_ok());
        assert_eq!(normal.volume(), 17 * 25 * 17);
    }

    fn a_shell_chunk_decomposes_and_recomposes_an_address() {
        let shells = ShellModel::default();
        let a = BlockAddress {
            sector: Sector::NegZ,
            shell: 7,
            i: 12345,
            j: 6789,
            layer: 900,
        };
        let chunk = ShellChunk::of(&a);
        let (_, _, _, local) = a.chunk();
        assert_eq!(chunk.address(local), a);
        assert_eq!(chunk.shell, 7);
        assert!(chunk.address([16, 0, 0]).i == chunk.i * 16);
        let _ = shells;
    }

    #[test]
    fn one_block_at_a_shell_boundary_is_four_above_and_one_below() {
        let shells = ShellModel::default();
        let k = 6;
        let n = shells.resolution(k);
        let a = BlockAddress {
            sector: Sector::PosZ,
            shell: k,
            i: n / 3,
            j: n / 4,
            layer: shells.layers(k) - 1,
        };
        let up = a.above(&shells);
        assert_eq!(up.len(), 4);
        for child in &up {
            assert_eq!(child.shell, k + 1);
            assert_eq!(child.layer, 0);
            assert_eq!(child.below(&shells), vec![a]);
            assert!(BlockShape::from_address(&shells, child).is_valid());
        }
    }

    #[test]
    fn absurd_radii_return_no_geometry_instead_of_nan() {
        // f64 stops resolving single blocks beyond 2^53; the model must refuse
        // those shapes rather than hand the renderer NaN or zero-width cells.
        let planet = Planet::new(9_000_000_000_000_000_000, 3);
        let surface = planet.surface_radius_at(Vec3::X);
        let p = Vec3::X * (surface - 20.0);
        let geometry = planet.geometry_at(p);
        assert!(geometry.is_none() || geometry.map(|g| g.is_valid()).unwrap_or(false));
        assert!(!geometry.map(|g| g.min_edge()).unwrap_or(1.0).is_nan());
    }
}
