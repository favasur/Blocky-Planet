// Draw the Blocky Planet entry and overlay on the title's Create New World
// screen. `world_create` is Horizon's stable screen id (API 326); drawing over
// it is explicitly supported, so this needs no Java-style screen mixin.
fn blocky_planet_settings_screen(
    game: &crate::game::Game<'_>,
    batch: &mut horizon_client::hud::HudBatch,
    screen: &horizon_client::mod_api::NativeScreenView<'_>,
) {
    crate::game::blocky_planet::draw_create_world(game, batch, screen);
}
