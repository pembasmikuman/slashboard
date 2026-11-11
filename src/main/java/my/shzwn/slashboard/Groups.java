package my.shzwn.slashboard;

import java.util.List;
import java.util.Map;

/** Sorts root commands into sidebar groups. Anything unknown lands in {@link #OTHER}. */
public final class Groups {
	public static final String OTHER = "Mods / other";
	public static final List<String> ORDER = List.of("World", "Player", "Items", "Teleport", "Server", OTHER);

	private static final Map<String, String> BY_COMMAND = build(Map.of(
		"World", "time weather gamerule difficulty worldborder setworldspawn locate seed fill fillbiome setblock clone forceload summon place tick particle playsound stopsound",
		"Player", "gamemode defaultgamemode effect enchant experience xp kill spawnpoint attribute damage ride ability",
		"Items", "give item loot clear",
		"Teleport", "teleport tp spreadplayers spectate rotate",
		"Server", "op deop ban ban-ip banlist pardon pardon-ip kick whitelist list say me msg tell w teammsg tm tellraw title stop save-all save-on save-off reload datapack perf debug publish setidletimeout transfer scoreboard team tag trigger bossbar function schedule execute data return random jfr help version waypoint dialog test"
	));

	private Groups() {}

	public static String of(String command) {
		return BY_COMMAND.getOrDefault(command, OTHER);
	}

	private static Map<String, String> build(Map<String, String> groups) {
		var out = new java.util.HashMap<String, String>();
		groups.forEach((group, names) -> {
			for (String name : names.split(" ")) out.put(name, group);
		});
		return Map.copyOf(out);
	}
}
