// A small box in the bottom-left with the planet's radius, the player's block
// address and the block under them. It is the model's first window into the
// game: no world writes yet, just proof that planet space is being read.
fn blocky_planet_hud(
    game: &mut crate::game::Game<'_>,
    batch: &mut horizon_client::hud::HudBatch,
    corners: &mut horizon_client::extras_hud::Corners,
) {
    use crate::game::blocky_planet as m;
    use horizon_client::extras::Corner;

    if !game.native_on(m::ID) || !game.native_corner_shown(Corner::BottomLeft) {
        return;
    }
    let Some(p) = game.native_player() else {
        return;
    };
    let planet = m::planet();
    let pos = m::planet_position(p.x as f64, p.y as f64, p.z as f64);
    let lines = m::hud_lines(&planet, pos);

    let w = lines
        .iter()
        .map(|l| game.native_text_width(l))
        .fold(0.0, f32::max)
        + 4.0;
    let h = 10.0 * lines.len() as f32 + 2.0;
    let (x, y) = corners.place(Corner::BottomLeft, w, h);
    game.native_fill(batch, [x, y, w, h], m::BACKGROUND);
    for (i, line) in lines.iter().enumerate() {
        game.native_text(batch, line, [x + 2.0, y + 2.0 + 10.0 * i as f32], m::INK, true);
    }
}
