package my.shzwn.slashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the item builder holds for one /give item: name, lore, enchantments, extras and firework data.
 * {@link #components} turns it into the {@code [...]} part of an item argument, e.g.
 * {@code [custom_name={text:"Excalibur",italic:false},enchantments={"minecraft:sharpness":10}]}.
 * Plain Java with no game classes, so it can be unit-tested.
 */
final class ItemSpec {
	/** Vanilla's 16 named text colours, in the order the swatches show them. */
	static final Map<String, Integer> NAME_COLORS = new LinkedHashMap<>();
	static {
		String[] names = {"white", "gray", "dark_gray", "black", "red", "dark_red", "gold", "yellow",
			"green", "dark_green", "aqua", "dark_aqua", "blue", "dark_blue", "light_purple", "dark_purple"};
		int[] rgb = {0xFFFFFF, 0xAAAAAA, 0x555555, 0x000000, 0xFF5555, 0xAA0000, 0xFFAA00, 0xFFFF55,
			0x55FF55, 0x00AA00, 0x55FFFF, 0x00AAAA, 0x5555FF, 0x0000AA, 0xFF55FF, 0xAA00AA};
		for (int i = 0; i < names.length; i++) NAME_COLORS.put(names[i], rgb[i]);
	}
	static final List<String> SHAPES = List.of("small_ball", "large_ball", "star", "creeper", "burst");

	static final class Ench {
		String id = "", level = "1";
	}

	static final class Explosion {
		String shape = "small_ball";
		final Set<Integer> colors = new LinkedHashSet<>(), fade = new LinkedHashSet<>();
		boolean trail, twinkle;
	}

	String name = "", nameColor;
	boolean bold, italic;
	final List<String> lore = new ArrayList<>();
	final List<Ench> enchants = new ArrayList<>();
	boolean unbreakable, glow;
	int flight = 1;
	final List<Explosion> explosions = new ArrayList<>();

	/** The item id part of an item argument, without any {@code [...]} components. */
	static String baseOf(String item) {
		int i = item.indexOf('[');
		return (i < 0 ? item : item.substring(0, i)).strip();
	}

	static boolean isRocket(String base) {
		return base.equals("firework_rocket") || base.equals("minecraft:firework_rocket");
	}

	/** The {@code [...]} text to append to the item id, or "" when nothing is set. */
	String components(boolean rocket) {
		List<String> parts = new ArrayList<>();
		if (!name.isBlank()) {
			StringBuilder s = new StringBuilder("custom_name={text:").append(quote(name)).append(",italic:").append(italic);
			if (nameColor != null) s.append(",color:").append(quote(nameColor));
			if (bold) s.append(",bold:true");
			parts.add(s.append('}').toString());
		}
		if (!lore.isEmpty()) parts.add("lore=[" + String.join(",", lore.stream().map(ItemSpec::quote).toList()) + "]");
		List<String> ench = new ArrayList<>();
		for (Ench e : enchants) if (!e.id.isBlank()) ench.add(quote(e.id.strip()) + ":" + level(e.level));
		if (!ench.isEmpty()) parts.add("enchantments={" + String.join(",", ench) + "}");
		if (unbreakable) parts.add("unbreakable={}");
		if (glow) parts.add("enchantment_glint_override=true");
		if (rocket && (flight != 1 || !explosions.isEmpty())) {
			List<String> ex = new ArrayList<>();
			for (Explosion e : explosions) {
				StringBuilder s = new StringBuilder("{shape:").append(quote(e.shape));
				if (!e.colors.isEmpty()) s.append(",colors:").append(intArray(e.colors));
				if (!e.fade.isEmpty()) s.append(",fade_colors:").append(intArray(e.fade));
				if (e.trail) s.append(",has_trail:true");
				if (e.twinkle) s.append(",has_twinkle:true");
				ex.add(s.append('}').toString());
			}
			parts.add("fireworks={flight_duration:" + flight + ",explosions:[" + String.join(",", ex) + "]}");
		}
		return parts.isEmpty() ? "" : "[" + String.join(",", parts) + "]";
	}

	/** Enchantment level from the level box: blank or junk becomes 1, and it's kept inside vanilla's 1 to 255. */
	static int level(String text) {
		try {
			return Math.clamp(Integer.parseInt(text.strip()), 1, 255);
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static String intArray(Set<Integer> values) {
		return "[I;" + String.join(",", values.stream().map(String::valueOf).toList()) + "]";
	}

	static String quote(String s) {
		return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}
}
