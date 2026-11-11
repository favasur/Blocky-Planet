// /planet                     the planet and the player's address
// /planet radius <blocks>     make the planet bigger or smaller, any size
// /planet seed <n>            change the terrain
// /planet probe               count solid blocks in the player's local window
fn blocky_planet_command(game: &mut crate::game::Game<'_>, command: &str, ran: &mut bool) {
    use crate::game::blocky_planet as m;
    use blocky_planet_core::LocalWindow;

    let mut parts = command.split_whitespace();
    if !game.native_on(m::ID) || parts.next() != Some("planet") {
        return;
    }
    *ran = true;

    match parts.next() {
        None => {
            let planet = m::planet();
            game.native_chat(&format!(
                "{} v{}: radius {} blocks, {} shells",
                m::NAME,
                m::VERSION,
                m::thousands(m::radius()),
                planet.shell_count()
            ));
            match game.native_player() {
                Some(p) => {
                    let pos = m::planet_position(p.x as f64, p.y as f64, p.z as f64);
                    let a = planet.address_of(pos);
                    game.native_chat(&format!(
                        "You are at {} i {} j {} layer {} (r {:.0})",
                        a.sector.name(),
                        m::thousands_u128(a.i),
                        m::thousands_u128(a.j),
                        m::thousands_u128(a.layer),
                        pos.length()
                    ));
                }
                None => game.native_chat("Not in a world"),
            }
        }
        Some("radius") => match parts.next().and_then(|v| v.parse::<u64>().ok()) {
            Some(r) if r >= 64 => {
                m::set_radius(r);
                let planet = m::planet();
                game.native_chat(&format!(
                    "Planet radius is now {} blocks ({} shells, surface shell {})",
                    m::thousands(r),
                    planet.shell_count(),
                    planet.shells.shell_of(planet.radius)
                ));
            }
            _ => game.native_chat("Usage: /planet radius <blocks, 64 or more>"),
        },
        Some("seed") => match parts.next().and_then(|v| v.parse::<u64>().ok()) {
            Some(s) => {
                m::set_seed(s);
                game.native_chat(&format!("Planet seed is now {s}"));
            }
            None => game.native_chat("Usage: /planet seed <number>"),
        },
        Some("probe") => match game.native_player() {
            Some(p) => {
                let planet = m::planet();
                let feet = m::planet_position(p.x as f64, p.y as f64, p.z as f64);
                let frame = blocky_planet_core::LocalFrame::at(feet);
                let window = LocalWindow::new([-8, -16, -8], [8, 0, 8]);
                let (solid, total) = window.count(&planet, &frame);
                game.native_chat(&format!(
                    "Local 17x17x17 window: {solid}/{total} solid ({:.0}%)",
                    solid as f64 / total as f64 * 100.0
                ));
            }
            None => game.native_chat("Not in a world"),
        },
        Some(_) => game.native_chat("Usage: /planet [radius <n> | seed <n> | probe]"),
    }
}
