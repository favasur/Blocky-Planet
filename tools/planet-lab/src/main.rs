//! `planet-lab`: see what the model does, at any radius, without the game.
//!
//!   cargo run -p planet-lab                 # Earth-sized
//!   cargo run -p planet-lab -- 500          # a small moon
//!   cargo run -p planet-lab -- 12742000 42  # diameter 12,742,000, seed 42

use blocky_planet_core::{Block, LocalFrame, Planet, Vec3, ALL_SECTORS};
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
        "  diameter          {} blocks",
        thousands(radius.saturating_mul(2))
    );
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

    let at = Vec3::new(planet.radius, 0.0, 0.0);
    let surf_x = planet.surface_radius_at(Vec3::X);
    let address = planet.address_of(Vec3::X * surf_x);
    println!(
        "  address at +X     sector {} shell {} i {} j {} layer {}",
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

    // Six sectors, each sampled at its centre: the same surface rules apply,
    // with no pole and no seam in the middle of a face.
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
    let _ = at;

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
    let window = blocky_planet_core::LocalWindow::new([-16, -32, -16], [16, 16, 16]);
    let (solid, total) = window.count(&planet, &frame);
    println!(
        "\nLocal window 33x49x33: {solid}/{total} solid blocks ({:.1}%)",
        solid as f64 / total as f64 * 100.0
    );
    println!(
        "  local origin in planet space: ({:.0}, {:.0}, {:.0})",
        frame.origin.x, frame.origin.y, frame.origin.z
    );

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

fn thousands(mut n: u64) -> String {
    if n == 0 {
        return "0".into();
    }
    let mut parts = Vec::new();
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
