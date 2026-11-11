// Take clicks on Blocky Planet's Create New World overlay before the title's
// screen sees them. Cancelling is what keeps a click on the overlay from also
// pressing the Create button underneath it.
fn blocky_planet_settings_click(
    game: &crate::game::Game<'_>,
    screen: &horizon_client::mod_api::NativeScreenView<'_>,
    button: i32,
    canceled: &mut bool,
) {
    crate::game::blocky_planet::click_create_world(game, screen, button, canceled);
}
