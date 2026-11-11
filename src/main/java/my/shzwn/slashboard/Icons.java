package my.shzwn.slashboard;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Map;

/** 7x7 pixel icons for the sidebar tabs, drawn as plain fills so they can take any colour. */
final class Icons {
	static final int SIZE = 7;

	private static final Map<String, String[]> ICONS = Map.of(
		"Presets", new String[] {
			"#######",
			"#######",
			"#######",
			"#######",
			"#######",
			"###.###",
			"##...##"},
		"Recent", new String[] {
			"..###..",
			".#.#.#.",
			"#..#..#",
			"#..##.#",
			"#.....#",
			".#...#.",
			"..###.."},
		"World", new String[] {
			"..###..",
			".#.#.#.",
			"#..#..#",
			"#######",
			"#..#..#",
			".#.#.#.",
			"..###.."},
		"Player", new String[] {
			"..###..",
			"..###..",
			"..###..",
			".......",
			".#####.",
			"#######",
			"#######"},
		"Items", new String[] {
			"#######",
			"#.....#",
			"#######",
			"#..#..#",
			"#.....#",
			"#.....#",
			"#######"},
		"Teleport", new String[] {
			"..###..",
			".#####.",
			"##...##",
			"##...##",
			".#####.",
			"..###..",
			"...#..."},
		"Server", new String[] {
			"#######",
			"#.#...#",
			"#######",
			".......",
			"#######",
			"#.#...#",
			"#######"},
		Groups.OTHER, new String[] {
			"..##...",
			"######.",
			"######.",
			"#######",
			"#######",
			"######.",
			"######."});

	private Icons() {}

	/** Draws the icon for a sidebar tab at (x, y), or nothing if the tab has no icon. */
	static void draw(GuiGraphicsExtractor g, String tab, int x, int y, int color) {
		String[] rows = ICONS.get(tab);
		if (rows == null) return;
		for (int r = 0; r < rows.length; r++) {
			String row = rows[r];
			for (int c = 0; c < row.length(); c++) {
				if (row.charAt(c) == '#') g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
			}
		}
	}
}
