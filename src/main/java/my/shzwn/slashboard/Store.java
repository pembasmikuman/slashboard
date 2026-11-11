package my.shzwn.slashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Presets and recent history, kept in {@code config/slashboard.json}. */
public final class Store {
	public static final int RECENT_LIMIT = 20;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public record Preset(String name, String command) {}

	private static final class Data {
		List<Preset> presets = new ArrayList<>();
		List<String> recent = new ArrayList<>();
	}

	private final Path file;
	private Data data = new Data();

	public Store(Path file) {
		this.file = file;
		load();
	}

	public List<Preset> presets() {
		return data.presets;
	}

	public List<String> recent() {
		return data.recent;
	}

	public void addPreset(String name, String command) {
		data.presets.add(new Preset(name, command));
		save();
	}

	public void removePreset(Preset preset) {
		data.presets.remove(preset);
		save();
	}

	/** Moves {@code command} to the top of recent, dropping the oldest past the limit. */
	public void pushRecent(String command) {
		data.recent.remove(command);
		data.recent.addFirst(command);
		while (data.recent.size() > RECENT_LIMIT) data.recent.removeLast();
		save();
	}

	private void load() {
		if (!Files.exists(file)) return;
		try {
			Data loaded = GSON.fromJson(Files.readString(file), Data.class);
			if (loaded == null) return;
			if (loaded.presets == null) loaded.presets = new ArrayList<>();
			if (loaded.recent == null) loaded.recent = new ArrayList<>();
			loaded.presets.removeIf(p -> p == null || p.command() == null || p.name() == null);
			loaded.recent.removeIf(r -> r == null);
			data = loaded;
		} catch (IOException | JsonParseException e) {
			// Keep the broken file so hand-made presets can be recovered, then start fresh.
			SlashboardClient.LOG.error("Couldn't read {}, moving it to .bad and starting empty", file, e);
			try {
				Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException moveError) {
				SlashboardClient.LOG.error("Couldn't move broken config aside", moveError);
			}
		}
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(data));
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SlashboardClient.LOG.error("Couldn't save {}", file, e);
		}
	}
}
