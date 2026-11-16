/**
 * Screen code that both sides run. A server builds every LDLib screen as a client does, to hold the screen's data, so
 * what a block's or an item's screen is drawn and dressed with lives here and not in {@code dev.logos.client}.
 *
 * <p>A dedicated server has no client classes: nothing here may reach one except behind a check of which side it is on,
 * and each such place is listed in {@code ClientBoundaryTest}.
 */
package dev.logos.ui;
