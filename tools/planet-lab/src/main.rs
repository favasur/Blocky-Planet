//! `planet-lab`: see what the model does, at any radius, without the game.
//!
//!   cargo run -p planet-lab                 # Earth-sized
//!   cargo run -p planet-lab -- 500          # a small moon
//!   cargo run -p planet-lab -- 12742000 42  # radius 12,742,000, seed 42

use blocky_planet_core::{Block, LocalFrame, LocalWindow, Planet, Vec3, ALL_SECTORS};
use std::time::Instant;

fn main() {
    let mut args = std::env::args().skip(1);
    let radius: u64 = args
        .next()
        .and_then(|a| a.replace('_', "").parse().ok())
        .unwrap_or(6_371_000);
    let seed: u64 = args.next().and_then(|a| a.parse().ok()).unwrap_or(1);

    let planet = Planet::new(radius, seed);
    let shells = planet.shells;
    let k = shells.shell_of(planet.radius);

    println!("Blocky Planet model");
    println!("  radius            {} blocks", thousands(radius));
    println!(
        "  diameter          {} blocks ({} km)",
        thousands(radius.saturating_mul(2)),
        thousands_u128((radius as f64 * 2.0 / 1000.0).round() as u128)
    );
    println!("  type              {}", planet.profile.kind.label());
    println!("  shells            {}", planet.shell_count());
    println!(
        "  surface shell     {k}  ({}..{:.0} blocks)",
        shells.inner_radius(k),
        shells.outer_radius(k)
    );
    println!(
        "  blocks per side   {}",
        thousands_u128(shells.resolution(k))
    );
    println!(
        "  chunks per side   {}",
        thousands_u128(shells.chunks_per_side(k))
    );
    println!(
        "  chunks per shell  {}  (16x16x16)",
        thousands_u128(
            shells.chunks_per_side(k) * shells.chunks_per_side(k) * shells.chunks_radial(k)
        )
    );

    println!("\nLayers (real Earth values, scaled to this radius)");
    for layer in &planet.profile.layers {
        let km = layer.thickness_fraction() * planet.radius / 1000.0;
        let blocks = layer.thickness_fraction() * planet.radius;
        println!(
            "  {:<12} {:>10} km {:>14} blocks  {:>5.2}% volume",
            layer.name(),
            thousands_u128(km.round() as u128),
            thousands_u128(blocks.round() as u128),
            layer.volume_share() * 100.0
        );
    }

    let surf_x = planet.surface_radius_at(Vec3::X);
    let address = planet.address_of(Vec3::X * surf_x);
    println!(
        "\n  address at +X     sector {} shell {} i {} j {} layer {}",
        address.sector.name(),
        address.shell,
        address.i,
        address.j,
        address.layer
    );
    println!(
        "  block at +X       {:?}   (surface {:.1}, mean {:.1})",
        planet.block_at(Vec3::X * (surf_x - 0.5)),
        surf_x,
        planet.radius
    );

    // The planet is solid; generation still only asks for a bounded band.
    let band_depth = 256.0;
    println!(
        "  generated band    {:.0} blocks = {:.4}% of the radius (planet itself is solid)",
        band_depth,
        100.0 * band_depth / planet.radius
    );
    let core = Vec3::X * (planet.radius * 0.10);
    println!(
        "  block at 0.10R    {:?}  ({}; shell {})",
        planet.block_at(core),
        planet
            .geometry_at(core)
            .map(|g| g.describe())
            .unwrap_or_else(|| "no solid geometry".to_string()),
        planet.address_of(core).shell
    );

    print!("  sector surfaces  ");
    for s in ALL_SECTORS {
        let dir = s.position(0.0, 0.0, 1.0);
        let surf = planet.surface_radius_at(dir);
        print!(
            "{}={} ",
            s.name(),
            planet.block_at(dir * (surf - 0.5)).ascii()
        );
    }
    println!();

    println!(
        "\nGreat-circle cross-section (z = 0), 1 char ~= {} blocks",
        {
            let span = (planet.radius * 2.2) / 96.0;
            thousands_u128(span.max(1.0) as u128)
        }
    );
    let w = 96;
    let h = 40;
    for row in 0..h {
        let mut line = String::with_capacity(w);
        for col in 0..w {
            let x = (col as f64 / (w - 1) as f64) * 2.2 - 1.1;
            let y = 1.1 - (row as f64 / (h - 1) as f64) * 2.2;
            let p = Vec3::new(x * planet.radius, y * planet.radius, 0.0);
            let b = planet.block_at(p);
            line.push(if b == Block::Air && p.length() > planet.radius {
                ' '
            } else {
                b.ascii()
            });
        }
        println!("{line}");
    }

    println!("\nSurface map (equirectangular, display only: the model has no poles)");
    let (mw, mh) = (72usize, 22usize);
    for row in 0..mh {
        let lat = 90.0 - (row as f64 + 0.5) / mh as f64 * 180.0;
        let mut line = String::with_capacity(mw);
        for col in 0..mw {
            let lon = -180.0 + (col as f64 + 0.5) / mw as f64 * 360.0;
            let (la, lo) = (lat.to_radians(), lon.to_radians());
            let dir = Vec3::new(la.cos() * lo.cos(), la.sin(), la.cos() * lo.sin());
            // Sample just below sea level: land shows its surface block,
            // basins show water. (Sampling at the surface itself would show
            // grass everywhere by definition.)
            line.push(planet.block_at(dir * (planet.radius - 0.5)).ascii());
        }
        println!("{line}");
    }

    // The local window: this is what the engine would receive. It is tiny even
    // when the planet is astronomically large.
    let feet = Vec3::new(planet.radius + 2.0, 0.0, 0.0);
    let frame = LocalFrame::at(feet);
    let window = LocalWindow::new([-16, -32, -16], [16, 16, 16]);
    let (solid, total) = window.count(&planet, &frame);
    println!(
        "\nLocal window 33x49x33: {solid}/{total} solid blocks ({:.1}%)",
        solid as f64 / total as f64 * 100.0
    );
    println!(
        "  local origin in planet space: ({:.0}, {:.0}, {:.0})",
        frame.origin.x, frame.origin.y, frame.origin.z
    );

    // A 16x16x64 chunk (the surface band a renderer would ask for), once
    // sampling noise per block and once sampling it once per column.
    let chunk_blocks = 16 * 16 * 64;
    let t0 = Instant::now();
    let mut naive_solid = 0u32;
    for x in 0..16 {
        for z in 0..16 {
            for y in 0..64 {
                let d = (Vec3::new(x as f64, y as f64, z as f64) * 0.02 + Vec3::X).normalized();
                let surface = planet.surface_radius_at(d);
                if planet.block_at(d * (surface - 0.5 - y as f64)).is_solid() {
                    naive_solid += 1;
                }
            }
        }
    }
    let naive_time = t0.elapsed();
    let t1 = Instant::now();
    let mut planned_solid = 0u32;
    for x in 0..16 {
        for z in 0..16 {
            let d = (Vec3::new(x as f64, 0.0, z as f64) * 0.02 + Vec3::X).normalized();
            let column = planet.column(d);
            for y in 0..64 {
                if column
                    .block_at(&planet, d, column.surface - 0.5 - y as f64)
                    .is_solid()
                {
                    planned_solid += 1;
                }
            }
        }
    }
    let planned_time = t1.elapsed();
    println!(
        "\n{chunk_blocks} blocks (16x16x64): per-block noise {:.1} ms, per-column {:.1} ms ({:.1}x)",
        naive_time.as_secs_f64() * 1000.0,
        planned_time.as_secs_f64() * 1000.0,
        naive_time.as_secs_f64() / planned_time.as_secs_f64()
    );
    println!("  solid: naive {naive_solid}, planned {planned_solid}");

    let n = 200_000u32;
    let start = Instant::now();
    let mut solid = 0u32;
    let mut h = seed | 1;
    for _ in 0..n {
        h = h
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        let dir = Vec3::new(
            ((h >> 11) as f64 / (1u64 << 53) as f64) * 2.0 - 1.0,
            ((h >> 33) as f64 / (1u32 << 20) as f64) - 1.0,
            ((h >> 3) as f64 / (1u64 << 53) as f64) * 2.0 - 1.0,
        )
        .normalized();
        let jitter = ((h >> 20) % 400) as f64 - 200.0;
        if planet.block_at(dir * (planet.radius + jitter)).is_solid() {
            solid += 1;
        }
    }
    let elapsed = start.elapsed();
    println!(
        "\n{n} block lookups in {:.1} ms ({:.2} us each), {solid} solid",
        elapsed.as_secs_f64() * 1000.0,
        elapsed.as_secs_f64() * 1e6 / n as f64
    );
}

fn thousands(n: u64) -> String {
    if n == 0 {
        return "0".into();
    }
    let mut parts = Vec::new();
    let mut n = n;
    while n > 0 {
        parts.push(n % 1000);
        n /= 1000;
    }
    let mut out = String::new();
    for (i, p) in parts.iter().rev().enumerate() {
        if i > 0 {
            out.push(',');
        }
        if i == 0 {
            out.push_str(&p.to_string());
        } else {
            out.push_str(&format!("{p:03}"));
        }
    }
    out
}

fn thousands_u128(n: u128) -> String {
    let digits = n.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out
}
