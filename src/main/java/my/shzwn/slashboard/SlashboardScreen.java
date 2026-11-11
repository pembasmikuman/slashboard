package my.shzwn.slashboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import my.shzwn.slashboard.CommandForm.Choice;
import my.shzwn.slashboard.CommandForm.Field;
import my.shzwn.slashboard.CommandForm.Raw;
import my.shzwn.slashboard.CommandForm.Step;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.DyeColor;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * The Slashboard menu. Drawn immediate-mode: every frame lays out the panel, draws it, and records
 * clickable areas in {@link #hits}, which the next click is tested against. Text input uses vanilla
 * {@link EditBox}es (unbordered) placed inside our own field boxes.
 *
 * Motion follows Emil Kowalski's rules: no open/close animation (keyboard-triggered, used constantly),
 * instant hover, press feedback while the mouse is held, and one fading confirmation line.
 */
public final class SlashboardScreen extends Screen {
	private static final int PANEL = 0xF2121318, LINE = 0x24FFFFFF, HOVER = 0x16FFFFFF,
		SEL_BG = 0x403D7BFF, SEL_TEXT = 0xFFA9C6FF, TEXT = 0xFFE6E6EA, TEXT_2 = 0xFFA3A3AD, TEXT_3 = 0xFF6E6E7A,
		FIELD = 0xFF0B0B0F, FIELD_LINE = 0x30FFFFFF, FOCUS = 0xFF4F8BFF, ACCENT = 0xFF3F7BF0, ACCENT_PRESS = 0xFF2C5FC4,
		DANGER = 0xFFFF8080, WARN = 0xFFE5B85C, SUCCESS = 0xFF7ED492, DROP = 0xFF1B1C22;
	private static final int SIDEBAR_W = 92, LIST_W = 116, ROW = 14, PAD = 10, FIELD_H = 16, MAX_DROP = 6;
	private static final String PRESETS = "Presets", RECENT = "Recent";
	private static final Object RAW_KEY = new Object();
	private static final long FLASH_MS = 1400;

	private record Hit(int x, int y, int w, int h, String id, Consumer<Boolean> onClick) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** One dropdown row: {@code start} is where {@code text} begins replacing, inside prefix + box value. */
	private record Sug(int start, String text) {}

	private record Chip(String label, boolean on, Runnable action) {}

	private static final List<String> SHAPE_LABELS = List.of("Small", "Large", "Star", "Creeper", "Burst");

	private final Store store = SlashboardClient.store;
	private final List<Hit> hits = new ArrayList<>();
	private final Map<String, List<LiteralCommandNode<ClientSuggestionProvider>>> groups = new LinkedHashMap<>();
	private final Map<Object, EditBox> boxes = new HashMap<>();
	private CommandDispatcher<ClientSuggestionProvider> dispatcher;
	private ClientSuggestionProvider source;

	private String view = PRESETS;
	private CommandForm<ClientSuggestionProvider> form;
	private EditBox search, nameBox;
	private boolean naming, opened;
	private int listScroll, formScroll, maxFormScroll;
	private int listTop, listBottom, listX, listW, formTop, formBottom, formX, formW;
	private String pressed;

	// Item builder for item arguments (/give). Its boxes use String keys, form boxes use node keys.
	private ItemSpec spec;
	private CommandNode<?> specNode;
	private boolean builderOpen, rewriting;
	private final Map<String, EditBox> specBoxes = new HashMap<>();
	private List<String> enchantIds;

	private EditBox sugFor;
	private String sugPrefix = "";
	private List<Sug> sugs = List.of();
	private int sugSel, sugTop, sugReq;
	private boolean sugDismissed;
	private int dropX, dropY, dropW, dropRows;

	private String checkedCommand, parseHint;
	private String flashText;
	private int flashColor;
	private long flashAt;
	// Hover tooltip on the command list: waits TIP_DELAY on the first row, then shows instantly while moving between rows.
	private static final int TIP_DELAY = 400;
	private String tipCmd;
	private long tipSince;
	private boolean tipWarm;

	public SlashboardScreen() {
		super(Component.literal("Slashboard"));
	}

	@Override
	protected void init() {
		ClientPacketListener conn = minecraft.getConnection();
		if (conn == null) {
			onClose();
			return;
		}
		if (dispatcher == null) {
			dispatcher = conn.getCommands();
			source = conn.getSuggestionsProvider();
			for (String g : Groups.ORDER) groups.put(g, new ArrayList<>());
			for (CommandNode<ClientSuggestionProvider> child : dispatcher.getRoot().getChildren()) {
				if (child instanceof LiteralCommandNode<ClientSuggestionProvider> lit) groups.get(Groups.of(lit.getLiteral())).add(lit);
			}
			groups.values().forEach(l -> l.sort(Comparator.comparing(LiteralCommandNode::getLiteral)));
			groups.values().removeIf(List::isEmpty);
		}

		// init() also runs on window resize after widgets are cleared, so rebuild every box here.
		String query = search == null ? "" : search.getValue();
		search = field(Component.literal("Search"));
		search.setHint(Component.literal("Search commands").withColor(TEXT_3));
		search.setValue(query);
		search.setResponder(v -> listScroll = 0);
		addRenderableWidget(search);

		boxes.clear();
		if (form != null) syncBoxes();
		specBoxes.clear();
		if (spec != null) rebuildSpecBoxes();
		if (naming) {
			String name = nameBox == null ? "" : nameBox.getValue();
			nameBox = field(Component.literal("Preset name"));
			nameBox.setValue(name);
			addRenderableWidget(nameBox);
		}
		if (!opened) {
			opened = true;
			setInitialFocus(search);
		}
	}

	private EditBox field(Component label) {
		EditBox b = new EditBox(font, 0, 0, 100, 12, label);
		b.setBordered(false);
		b.setMaxLength(32767);
		b.setTextColor(TEXT);
		b.setTextShadow(false);
		return b;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------- form state ----------

	private void openForm(LiteralCommandNode<ClientSuggestionProvider> node) {
		boxes.values().forEach(this::removeWidget);
		boxes.clear();
		clearSpec();
		cancelNaming();
		form = new CommandForm<>(node);
		formScroll = 0;
		sugFor = null;
		syncBoxes();
		setFocused(null);
		for (Step step : form.steps()) {
			EditBox first = boxes.get(keyOf(step));
			if (first != null) {
				setFocused(first);
				break;
			}
		}
	}

	private static Object keyOf(Step step) {
		return switch (step) {
			case Field<?> f -> f.node();
			case Choice<?> c -> c.useChips() ? null : c.parent();
			case Raw r -> RAW_KEY;
		};
	}

	private Step stepFor(Object key) {
		for (Step s : form.steps()) if (key.equals(keyOf(s))) return s;
		return null;
	}

	/** Adds a text box for every step that needs one and drops boxes whose step went away. */
	private void syncBoxes() {
		Set<Object> live = new HashSet<>();
		for (Step step : form.steps()) {
			Object key = keyOf(step);
			if (key == null) continue;
			live.add(key);
			if (boxes.containsKey(key)) continue;
			EditBox b = field(Component.literal(labelOf(step)));
			b.setHint(Component.literal(placeholderOf(step)).withColor(TEXT_3));
			b.setValue(switch (step) {
				case Field<?> f -> form.value(cast(f.node()));
				case Raw r -> form.raw();
				case Choice<?> c -> c.selected() < 0 ? "" : c.options().get(c.selected()).getName();
			});
			b.setResponder(v -> onBoxChanged(key, v));
			boxes.put(key, b);
			addRenderableWidget(b);
		}
		boxes.entrySet().removeIf(e -> {
			if (live.contains(e.getKey())) return false;
			removeWidget(e.getValue());
			return true;
		});
	}

	private void onBoxChanged(Object key, String value) {
		Step step = stepFor(key);
		if (step == null) return;
		switch (step) {
			case Field<?> f -> form.setValue(cast(f.node()), value);
			case Raw r -> form.setRaw(value);
			case Choice<?> c -> {
				int idx = -1;
				for (int i = 0; i < c.options().size(); i++) {
					if (c.options().get(i).getName().equals(value.strip())) idx = i;
				}
				form.choose(cast(c.parent()), idx);
			}
		}
		syncBoxes();
		if (!rewriting) requestSuggestions(key);
	}

	@SuppressWarnings("unchecked")
	private static CommandNode<ClientSuggestionProvider> cast(CommandNode<?> node) {
		return (CommandNode<ClientSuggestionProvider>) node;
	}

	private String labelOf(Step step) {
		return switch (step) {
			case Field<?> f -> {
				String range = rangeOf(f.node());
				String extra = f.optional() ? (range.isEmpty() ? "optional" : "optional, " + range) : range;
				yield extra.isEmpty() ? f.node().getName() : f.node().getName() + " (" + extra + ")";
			}
			case Choice<?> c -> (form.steps().getFirst().equals(step) ? "choose" : c.parent().getName())
				+ (c.optional() ? " (optional)" : "");
			case Raw r -> "rest of the command" + (r.optional() ? " (optional)" : "");
		};
	}

	private static String placeholderOf(Step step) {
		return switch (step) {
			case Field<?> f -> f.node().getExamples().stream().findFirst().orElse("");
			case Choice<?> c -> "Type to pick";
			case Raw r -> "say hello";
		};
	}

	private static String rangeOf(ArgumentCommandNode<?, ?> node) {
		Object t = node.getType();
		String min, max;
		switch (t) {
			case IntegerArgumentType i -> { min = i.getMinimum() == Integer.MIN_VALUE ? null : "" + i.getMinimum(); max = i.getMaximum() == Integer.MAX_VALUE ? null : "" + i.getMaximum(); }
			case LongArgumentType l -> { min = l.getMinimum() == Long.MIN_VALUE ? null : "" + l.getMinimum(); max = l.getMaximum() == Long.MAX_VALUE ? null : "" + l.getMaximum(); }
			case DoubleArgumentType d -> { min = d.getMinimum() == -Double.MAX_VALUE ? null : trim(d.getMinimum()); max = d.getMaximum() == Double.MAX_VALUE ? null : trim(d.getMaximum()); }
			case FloatArgumentType f -> { min = f.getMinimum() == -Float.MAX_VALUE ? null : trim(f.getMinimum()); max = f.getMaximum() == Float.MAX_VALUE ? null : trim(f.getMaximum()); }
			default -> { return ""; }
		}
		if (min != null && max != null) return min + " to " + max;
		if (min != null) return min + " or more";
		if (max != null) return "up to " + max;
		return "";
	}

	private static String trim(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}

	// ---------- suggestions ----------

	private void requestSuggestions(Object key) {
		if (key instanceof String k) {
			// Builder boxes: only enchantment ids get a dropdown.
			EditBox box = specBoxes.get(k);
			if (box == null || !k.startsWith("ench:")) return;
			startSugs(box);
			setSugs("", localSugs(box.getValue(), enchantIds()));
			return;
		}
		EditBox box = boxes.get(key);
		Step step = stepFor(key);
		if (box == null || step == null) return;
		startSugs(box);
		int req = ++sugReq;

		if (step instanceof Choice<?> c) {
			setSugs("", localSugs(box.getValue(), c.options().stream().map(CommandNode::getName).toList()));
			return;
		}

		String prefix = form.prefixBefore(step);
		String input = prefix + box.getValue();
		ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(input, source);
		// Same path the chat box uses, so modded and server-side suggestions arrive too.
		dispatcher.getCompletionSuggestions(parse, input.length()).thenAcceptAsync(s -> {
			if (req != sugReq) return;
			setSugs(prefix, s.getList().stream().map(x -> new Sug(x.getRange().getStart(), x.getText())).toList());
		}, minecraft);
	}

	private void startSugs(EditBox box) {
		if (sugFor != box) {
			sugSel = 0;
			sugTop = 0;
		}
		sugFor = box;
		sugDismissed = false;
	}

	/** Filters names against what's typed: matches at the start (of the id or the part after "ns:") first, then anywhere. */
	private static List<Sug> localSugs(String typed, Collection<String> names) {
		String q = typed.strip().toLowerCase(Locale.ROOT);
		List<Sug> starts = new ArrayList<>(), contains = new ArrayList<>();
		for (String name : names) {
			String n = name.toLowerCase(Locale.ROOT);
			if (n.startsWith(q) || n.substring(n.indexOf(':') + 1).startsWith(q)) starts.add(new Sug(0, name));
			else if (n.contains(q)) contains.add(new Sug(0, name));
		}
		starts.addAll(contains);
		return starts;
	}

	/** Every enchantment the server knows, modded ones included (the registry is sent on join). */
	private List<String> enchantIds() {
		if (enchantIds == null) {
			enchantIds = minecraft.getConnection().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
				.listElementIds().map(k -> k.identifier().toString()).sorted().toList();
		}
		return enchantIds;
	}

	private void setSugs(String prefix, List<Sug> list) {
		sugPrefix = prefix;
		sugs = list;
		sugSel = Math.min(sugSel, Math.max(0, list.size() - 1));
		sugTop = Math.min(sugTop, sugSel);
	}

	private String applied(Sug s) {
		String input = sugPrefix + sugFor.getValue();
		String out = input.substring(0, Math.min(s.start(), input.length())) + s.text();
		return out.length() >= sugPrefix.length() ? out.substring(sugPrefix.length()) : s.text();
	}

	private boolean dropdownOpen() {
		if (sugFor == null || !sugFor.isFocused() || !sugFor.isVisible() || sugDismissed || sugs.isEmpty()) return false;
		return !(sugs.size() == 1 && applied(sugs.getFirst()).equals(sugFor.getValue()));
	}

	private void acceptSuggestion(int index) {
		Sug s = sugs.get(index);
		sugFor.setValue(applied(s));
		sugFor.moveCursorToEnd(false);
	}

	// ---------- actions ----------

	private void runForm(boolean keepOpen) {
		List<String> missing = form.missing();
		if (!missing.isEmpty()) {
			flash("Fill in " + String.join(", ", missing) + " first", DANGER);
			return;
		}
		run(form.command(), keepOpen);
	}

	private void run(String command, boolean keepOpen) {
		ClientPacketListener conn = minecraft.getConnection();
		if (conn == null) return;
		conn.sendCommand(command);
		store.pushRecent(command);
		if (keepOpen) flash("Sent /" + command, SUCCESS);
		else onClose();
	}

	private void startNaming() {
		if (!form.missing().isEmpty()) {
			flash("Fill in " + String.join(", ", form.missing()) + " first", DANGER);
			return;
		}
		naming = true;
		nameBox = field(Component.literal("Preset name"));
		nameBox.setValue(form.command());
		nameBox.setHint(Component.literal("Preset name").withColor(TEXT_3));
		addRenderableWidget(nameBox);
		setFocused(nameBox);
	}

	private void savePreset() {
		String name = nameBox.getValue().strip();
		store.addPreset(name.isEmpty() ? form.command() : name, form.command());
		cancelNaming();
		flash("Saved to presets", SUCCESS);
	}

	private void cancelNaming() {
		if (nameBox != null) removeWidget(nameBox);
		nameBox = null;
		naming = false;
	}

	private void flash(String text, int color) {
		flashText = text;
		flashColor = color;
		flashAt = Util.getMillis();
	}

	private List<LiteralCommandNode<ClientSuggestionProvider>> listed() {
		String q = search.getValue().strip().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
		if (q.isEmpty()) return groups.getOrDefault(view, List.of());
		List<LiteralCommandNode<ClientSuggestionProvider>> starts = new ArrayList<>(), contains = new ArrayList<>();
		for (var list : groups.values()) for (var n : list) {
			if (n.getLiteral().startsWith(q)) starts.add(n);
			else if (n.getLiteral().contains(q)) contains.add(n);
		}
		starts.sort(Comparator.comparing(LiteralCommandNode::getLiteral));
		starts.addAll(contains);
		return starts;
	}

	private boolean savedView() {
		return search.getValue().isBlank() && (view.equals(PRESETS) || view.equals(RECENT));
	}

	// ---------- drawing ----------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		hits.clear();
		int pw = Math.min(width - 16, 460), ph = Math.min(height - 16, 280);
		int x0 = (width - pw) / 2, y0 = (height - ph) / 2;
		g.fill(x0, y0, x0 + pw, y0 + ph, PANEL);
		frame(g, x0, y0, pw, ph, LINE);

		drawSidebar(g, x0, y0, ph, mx, my);
		int lx = x0 + SIDEBAR_W;
		g.fill(lx, y0, lx + 1, y0 + ph, LINE);

		fieldBox(g, lx + 6, y0 + 6, LIST_W - 12, search);
		listX = lx + 4;
		listTop = y0 + 28;
		listBottom = y0 + ph - 6;
		if (savedView()) {
			listW = pw - SIDEBAR_W - 8;
			hideFormBoxes();
			drawSaved(g, mx, my, x0, y0, pw, ph);
		} else {
			listW = LIST_W - 8;
			int fx = lx + LIST_W;
			g.fill(fx, y0, fx + 1, y0 + ph, LINE);
			drawCommandList(g, mx, my);
			drawForm(g, mx, my, fx + 1, y0, x0 + pw - fx - 1, ph);
		}

		// Poll focus so tabbing or clicking into a field shows its suggestions.
		if (form != null && getFocused() instanceof EditBox b && b != sugFor) {
			boxes.forEach((k, v) -> { if (v == b) requestSuggestions(k); });
			specBoxes.forEach((k, v) -> { if (v == b) requestSuggestions(k); });
		}

		super.extractRenderState(g, mx, my, dt);
		g.nextStratum();
		drawDropdown(g, mx, my);
	}

	private void drawSidebar(GuiGraphicsExtractor g, int x0, int y0, int ph, int mx, int my) {
		int y = y0 + 6;
		List<String> items = new ArrayList<>(List.of(PRESETS, RECENT));
		items.addAll(groups.keySet());
		for (String item : items) {
			if (item.equals(groups.keySet().stream().findFirst().orElse(null))) {
				g.fill(x0 + 8, y + 3, x0 + SIDEBAR_W - 8, y + 4, LINE);
				y += 7;
			}
			boolean selected = item.equals(view) && search.getValue().isBlank();
			row(g, x0 + 4, y, SIDEBAR_W - 8, 16, selected, mx, my);
			int color = selected ? SEL_TEXT : TEXT_2;
			Icons.draw(g, item, x0 + 10, y + 4, color);
			g.text(font, item, x0 + 12 + Icons.SIZE, y + 4, color, false);
			hits.add(new Hit(x0 + 4, y, SIDEBAR_W - 8, 16, "side:" + item, shift -> {
				view = item;
				listScroll = 0;
				search.setValue("");
			}));
			y += 17;
		}
	}

	private void drawCommandList(GuiGraphicsExtractor g, int mx, int my) {
		var list = listed();
		int visible = Math.max(1, (listBottom - listTop) / ROW);
		listScroll = clamp(listScroll, 0, Math.max(0, list.size() - visible));
		if (list.isEmpty()) {
			g.text(font, "No matches", listX + 6, listTop + 3, TEXT_3, false);
			return;
		}
		String hover = null;
		for (int i = 0; i < visible && i + listScroll < list.size(); i++) {
			var node = list.get(i + listScroll);
			int y = listTop + i * ROW;
			boolean selected = form != null && form.label().equals(node.getLiteral());
			if (!selected && in(mx, my, listX, y, listW, ROW)) hover = node.getLiteral();
			row(g, listX, y, listW, ROW, selected, mx, my);
			String label = font.plainSubstrByWidth("/" + node.getLiteral(), listW - 10);
			g.text(font, label, listX + 6, y + 3, selected ? SEL_TEXT : TEXT, false);
			hits.add(new Hit(listX, y, listW, ROW, "cmd:" + node.getLiteral(), shift -> openForm(node)));
		}
		scrollbar(g, listX + listW, listTop, listBottom - listTop, list.size(), visible, listScroll);

		long now = Util.getMillis();
		if (!Objects.equals(hover, tipCmd)) {
			tipWarm = tipCmd != null && (tipWarm || now - tipSince >= TIP_DELAY);
			tipCmd = hover;
			tipSince = now;
		}
		String desc = hover == null ? null : describe(hover);
		if (desc != null && (tipWarm || now - tipSince >= TIP_DELAY)) {
			g.setTooltipForNextFrame(font, Component.literal(desc), mx, my);
		}
	}

	/** Short plain-English description of a root command (from the lang file), or null if we don't have one. */
	private static String describe(String command) {
		String key = "slashboard.cmd." + command;
		return Language.getInstance().has(key) ? Language.getInstance().getOrDefault(key) : null;
	}

	private void drawSaved(GuiGraphicsExtractor g, int mx, int my, int x0, int y0, int pw, int ph) {
		boolean presets = view.equals(PRESETS);
		int rowH = presets ? 24 : ROW + 2;
		int count = presets ? store.presets().size() : store.recent().size();
		int visible = Math.max(1, (listBottom - listTop - 14) / rowH);
		listScroll = clamp(listScroll, 0, Math.max(0, count - visible));

		if (count == 0) {
			String a = presets ? "Save a filled-in command as a preset" : "Commands you run from here show up here";
			String b = presets ? "and it shows up here for one-click runs." : "so you can run them again in one click.";
			g.text(font, a, listX + 6, listTop + 4, TEXT_2, false);
			g.text(font, b, listX + 6, listTop + 16, TEXT_3, false);
		}
		for (int i = 0; i < visible && i + listScroll < count; i++) {
			int y = listTop + i * rowH;
			boolean hover = in(mx, my, listX, y, listW, rowH);
			row(g, listX, y, listW, rowH, false, mx, my);
			if (presets) {
				Store.Preset p = store.presets().get(i + listScroll);
				g.text(font, font.plainSubstrByWidth(p.name(), listW - 40), listX + 6, y + 3, TEXT, false);
				g.text(font, font.plainSubstrByWidth("/" + p.command(), listW - 40), listX + 6, y + 13, TEXT_3, false);
				hits.add(new Hit(listX, y, listW, rowH, "preset:" + i, shift -> run(p.command(), shift)));
				if (hover) iconButton(g, mx, my, listX + listW - 18, y + 5, "x", "del:" + i, shift -> store.removePreset(p));
			} else {
				String r = store.recent().get(i + listScroll);
				g.text(font, font.plainSubstrByWidth("/" + r, listW - 40), listX + 6, y + 4, TEXT, false);
				hits.add(new Hit(listX, y, listW, rowH, "recent:" + i, shift -> run(r, shift)));
				if (hover) iconButton(g, mx, my, listX + listW - 18, y + 1, "+", "pin:" + i, shift -> {
					store.addPreset(r, r);
					flash("Saved to presets", SUCCESS);
				});
			}
		}
		scrollbar(g, listX + listW, listTop, listBottom - listTop - 14, count, visible, listScroll);
		String hint = count == 0 ? "" : "Click to run. Shift-click keeps this open.";
		if (!drawFlash(g, listX + 6, listBottom - 10)) g.text(font, hint, listX + 6, listBottom - 10, TEXT_3, false);
	}

	private void drawForm(GuiGraphicsExtractor g, int mx, int my, int fx, int y0, int fw, int ph) {
		formX = fx;
		formW = fw;
		if (form == null) {
			hideFormBoxes();
			g.centeredText(font, "Pick a command", fx + fw / 2, y0 + ph / 2 - 4, TEXT_3);
			return;
		}
		int x = fx + PAD, w = fw - PAD * 2;
		g.text(font, "/" + form.label(), x, y0 + 9, TEXT, false);
		formTop = y0 + 24;
		String desc = describe(form.label());
		if (desc != null) {
			var lines = font.split(Component.literal(desc), w);
			int n = Math.min(2, lines.size());
			for (int i = 0; i < n; i++) g.text(font, lines.get(i), x, y0 + 21 + i * 10, TEXT_3, false);
			formTop += n * 10;
		}
		formBottom = y0 + ph - 54;

		List<Step> steps = form.steps();
		int y = formTop - formScroll, firstHit = hits.size();
		specBoxes.values().forEach(b -> b.setVisible(false));
		g.enableScissor(fx, formTop, fx + fw, formBottom);
		for (Step step : steps) {
			g.text(font, font.plainSubstrByWidth(labelOf(step), w), x, y, TEXT_2, false);
			y += 11;
			EditBox box = boxes.get(keyOf(step));
			if (box != null) {
				boolean shown = y >= formTop && y + FIELD_H <= formBottom;
				CommandNode<?> itemNode = step instanceof Field<?> f && f.node().getType() instanceof ItemArgument ? f.node() : null;
				boolean building = itemNode != null && builderOpen && itemNode == specNode;
				int bw = font.width("Customise") + 12;
				fieldBox(g, x, y, itemNode == null ? w : w - bw - 4, box);
				box.setVisible(shown);
				if (itemNode != null) {
					button(g, mx, my, x + w - bw, y, bw, FIELD_H, building ? "Hide" : "Customise", false, "customise",
						shift -> toggleBuilder(itemNode));
				}
				y += FIELD_H;
				if (building) y = drawBuilder(g, mx, my, x, y + 6, w);
			} else if (step instanceof Choice<?> c) {
				y = drawChips(g, mx, my, c, x, y, w);
			}
			y += 7;
		}
		g.disableScissor();
		hits.subList(firstHit, hits.size()).removeIf(h -> h.y() < formTop || h.y() + h.h() > formBottom);
		maxFormScroll = Math.max(0, y + formScroll - formBottom);
		formScroll = clamp(formScroll, 0, maxFormScroll);
		if (maxFormScroll > 0) {
			int contentH = y + formScroll - formTop;
			scrollbar(g, fx + fw - 4, formTop, formBottom - formTop, contentH, formBottom - formTop, formScroll);
		}

		// Footer: preview, status, buttons.
		int by = y0 + ph - 50;
		g.fill(fx, by - 4, fx + fw, by - 3, LINE);
		String command = "/" + form.command();
		g.fill(x, by, x + w, by + 14, FIELD);
		g.text(font, font.plainSubstrByWidth(command, w - 8), x + 4, by + 3, TEXT_2, false);
		if (!drawFlash(g, x, by + 19)) {
			List<String> missing = form.missing();
			String hint = parseHint();
			if (!missing.isEmpty()) g.text(font, font.plainSubstrByWidth("Needs " + String.join(", ", missing), w), x, by + 19, TEXT_3, false);
			else if (hint != null) g.text(font, font.plainSubstrByWidth(hint, w), x, by + 19, WARN, false);
			else g.text(font, font.plainSubstrByWidth("Enter runs it. Shift keeps this open.", w), x, by + 19, TEXT_3, false);
		}

		int bh = 16, bY = by + 31;
		if (naming) {
			int saveW = font.width("Save") + 16, cancelW = font.width("Cancel") + 16;
			int nameW = w - saveW - cancelW - 8;
			fieldBox(g, x, bY, nameW, nameBox);
			nameBox.setVisible(true);
			button(g, mx, my, x + w - saveW, bY, saveW, bh, "Save", true, "save", shift -> savePreset());
			button(g, mx, my, x + w - saveW - 4 - cancelW, bY, cancelW, bh, "Cancel", false, "cancel", shift -> cancelNaming());
		} else {
			int runW = font.width("Run") + 24, presetW = font.width("Save preset") + 16;
			button(g, mx, my, x + w - runW, bY, runW, bh, "Run", true, "run", this::runForm);
			button(g, mx, my, x + w - runW - 4 - presetW, bY, presetW, bh, "Save preset", false, "preset", shift -> startNaming());
		}
	}

	private int drawChips(GuiGraphicsExtractor g, int mx, int my, Choice<?> c, int x, int y, int w) {
		var parent = cast(c.parent());
		List<Chip> chips = new ArrayList<>();
		int count = c.options().size() + (c.optional() ? 1 : 0);
		for (int i = 0; i < count; i++) {
			int index = i < c.options().size() ? i : -1;
			CommandNode<?> o = index < 0 ? null : c.options().get(index);
			String label = o == null ? "none" : o instanceof LiteralCommandNode<?> ? o.getName() : "<" + o.getName() + ">";
			chips.add(new Chip(label, c.selected() == index, () -> {
				form.choose(parent, index);
				syncBoxes();
			}));
		}
		return chipRow(g, mx, my, x, y, w, "chip:" + c.parent().getName(), chips);
	}

	/** Toggle chips that wrap onto new lines. Returns the y below the last row. */
	private int chipRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String id, List<Chip> chips) {
		int cx = x, h = 14;
		for (int i = 0; i < chips.size(); i++) {
			Chip c = chips.get(i);
			int cw = font.width(c.label()) + 12;
			if (cx + cw > x + w && cx > x) {
				cx = x;
				y += h + 4;
			}
			String cid = id + ":" + i;
			int sink = cid.equals(pressed) ? 1 : 0;
			if (c.on()) g.fill(cx, y + sink, cx + cw, y + h + sink, SEL_BG);
			else if (in(mx, my, cx, y, cw, h)) g.fill(cx, y + sink, cx + cw, y + h + sink, HOVER);
			frame(g, cx, y + sink, cw, h, c.on() ? FOCUS : FIELD_LINE);
			g.text(font, c.label(), cx + 6, y + 3 + sink, c.on() ? SEL_TEXT : TEXT_2, false);
			hits.add(new Hit(cx, y, cw, h, cid, shift -> c.action().run()));
			cx += cw + 4;
		}
		return y + h;
	}

	// ---------- item builder ----------

	private void toggleBuilder(CommandNode<?> node) {
		if (spec == null || specNode != node) {
			clearSpec();
			spec = new ItemSpec();
			specNode = node;
			rebuildSpecBoxes();
		}
		builderOpen = !builderOpen;
	}

	private void clearSpec() {
		specBoxes.values().forEach(this::removeWidget);
		specBoxes.clear();
		spec = null;
		specNode = null;
		builderOpen = false;
	}

	/** Recreates the builder's text boxes from the spec, after a row is added or removed or the window resizes. */
	private void rebuildSpecBoxes() {
		specBoxes.values().forEach(this::removeWidget);
		specBoxes.clear();
		specBox("name", spec.name, "Name shown on the item", v -> spec.name = v);
		for (int i = 0; i < spec.lore.size(); i++) {
			int n = i;
			specBox("lore:" + i, spec.lore.get(i), "Lore line", v -> spec.lore.set(n, v));
		}
		for (ItemSpec.Ench e : spec.enchants) {
			int n = spec.enchants.indexOf(e);
			specBox("ench:" + n, e.id, "sharpness", v -> e.id = v);
			specBox("lvl:" + n, e.level, "1", v -> e.level = v);
		}
	}

	private void specBox(String key, String value, String hint, Consumer<String> onChange) {
		EditBox b = field(Component.literal(hint));
		b.setHint(Component.literal(hint).withColor(TEXT_3));
		b.setValue(value);
		b.setResponder(v -> {
			onChange.accept(v);
			writeSpec();
			requestSuggestions(key);
		});
		b.setVisible(false);
		specBoxes.put(key, b);
		addRenderableWidget(b);
	}

	/** Writes the builder's components into the item box, keeping whatever item id is typed there. */
	private void writeSpec() {
		EditBox item = boxes.get(specNode);
		if (item == null) return;
		String base = ItemSpec.baseOf(item.getValue());
		rewriting = true;
		item.setValue(base + spec.components(ItemSpec.isRocket(base)));
		rewriting = false;
	}

	private Runnable edit(Runnable change) {
		return () -> {
			change.run();
			writeSpec();
		};
	}

	/** Adds a row with text boxes (lore line, enchantment) and puts the cursor in it. */
	private void addRow(Runnable add, String focusKey) {
		add.run();
		rebuildSpecBoxes();
		writeSpec();
		setFocused(specBoxes.get(focusKey));
	}

	private void removeRow(Runnable remove) {
		remove.run();
		rebuildSpecBoxes();
		writeSpec();
	}

	private int drawBuilder(GuiGraphicsExtractor g, int mx, int my, int x0, int y, int w0) {
		int top = y, x = x0 + 8, w = w0 - 8;

		y = label(g, "Name", x, y);
		EditBox nameBox = specBoxes.get("name");
		nameBox.setTextColor(spec.nameColor == null ? TEXT : 0xFF000000 | ItemSpec.NAME_COLORS.get(spec.nameColor));
		y = specField(g, "name", x, y, w) + 4;
		List<String> names = new ArrayList<>(ItemSpec.NAME_COLORS.keySet());
		y = swatches(g, mx, my, x, y, new ArrayList<>(ItemSpec.NAME_COLORS.values()), "ncol",
			i -> names.get(i).equals(spec.nameColor),
			i -> edit(() -> spec.nameColor = names.get(i).equals(spec.nameColor) ? null : names.get(i)).run()) + 2;
		y = chipRow(g, mx, my, x, y, w, "style", List.of(
			new Chip("Bold", spec.bold, edit(() -> spec.bold = !spec.bold)),
			new Chip("Italic", spec.italic, edit(() -> spec.italic = !spec.italic))));

		y = label(g, "Lore", x, y + 7);
		for (int i = 0; i < spec.lore.size(); i++) {
			int n = i;
			specField(g, "lore:" + i, x, y, w - 18);
			iconButton(g, mx, my, x + w - 14, y + 1, "x", "lore-x:" + i, shift -> removeRow(() -> spec.lore.remove(n)));
			y += FIELD_H + 3;
		}
		y = addLink(g, mx, my, x, y + 1, "+ add line", "lore+", () -> addRow(() -> spec.lore.add(""), "lore:" + spec.lore.size()));

		y = label(g, "Enchantments", x, y + 5);
		for (int i = 0; i < spec.enchants.size(); i++) {
			int n = i;
			specField(g, "ench:" + i, x, y, w - 56);
			specField(g, "lvl:" + i, x + w - 52, y, 34);
			iconButton(g, mx, my, x + w - 14, y + 1, "x", "ench-x:" + i, shift -> removeRow(() -> spec.enchants.remove(n)));
			y += FIELD_H + 3;
		}
		y = addLink(g, mx, my, x, y + 1, "+ add enchantment", "ench+",
			() -> addRow(() -> spec.enchants.add(new ItemSpec.Ench()), "ench:" + spec.enchants.size()));

		y = label(g, "Extras", x, y + 5);
		y = chipRow(g, mx, my, x, y, w, "extras", List.of(
			new Chip("Unbreakable", spec.unbreakable, edit(() -> spec.unbreakable = !spec.unbreakable)),
			new Chip("Glow", spec.glow, edit(() -> spec.glow = !spec.glow))));

		EditBox item = boxes.get(specNode);
		if (item != null && ItemSpec.isRocket(ItemSpec.baseOf(item.getValue()))) {
			y = label(g, "Flight", x, y + 7);
			List<Chip> flight = new ArrayList<>();
			for (int f = 1; f <= 3; f++) {
				int v = f;
				flight.add(new Chip(String.valueOf(v), spec.flight == v, edit(() -> spec.flight = v)));
			}
			y = chipRow(g, mx, my, x, y, w, "flight", flight);

			y = label(g, "Explosions", x, y + 7);
			List<Integer> dyes = Arrays.stream(DyeColor.values()).map(DyeColor::getFireworkColor).toList();
			for (int i = 0; i < spec.explosions.size(); i++) {
				ItemSpec.Explosion e = spec.explosions.get(i);
				int n = i, cardTop = y, cx = x + 5, cw = w - 10;
				List<Chip> shapes = new ArrayList<>();
				for (int k = 0; k < ItemSpec.SHAPES.size(); k++) {
					String shape = ItemSpec.SHAPES.get(k);
					shapes.add(new Chip(SHAPE_LABELS.get(k), e.shape.equals(shape), edit(() -> e.shape = shape)));
				}
				y = chipRow(g, mx, my, cx, y + 5, cw - 18, "shape" + i, shapes);
				iconButton(g, mx, my, x + w - 19, cardTop + 5, "x", "ex-x:" + i, shift -> edit(() -> spec.explosions.remove(n)).run());
				g.text(font, "Colours", cx, y + 5, TEXT_3, false);
				y = swatches(g, mx, my, cx, y + 16, dyes, "col" + i, k -> e.colors.contains(dyes.get(k)),
					k -> edit(() -> toggle(e.colors, dyes.get(k))).run());
				g.text(font, "Fade to", cx, y + 3, TEXT_3, false);
				y = swatches(g, mx, my, cx, y + 14, dyes, "fade" + i, k -> e.fade.contains(dyes.get(k)),
					k -> edit(() -> toggle(e.fade, dyes.get(k))).run());
				y = chipRow(g, mx, my, cx, y + 4, cw, "fx" + i, List.of(
					new Chip("Trail", e.trail, edit(() -> e.trail = !e.trail)),
					new Chip("Twinkle", e.twinkle, edit(() -> e.twinkle = !e.twinkle)))) + 5;
				frame(g, x, cardTop, w, y - cardTop, LINE);
				y += 4;
			}
			y = addLink(g, mx, my, x, y + 1, "+ add explosion", "ex+", edit(() -> spec.explosions.add(new ItemSpec.Explosion())));
		}
		g.fill(x0, top, x0 + 2, y, FOCUS);
		return y;
	}

	private static void toggle(Set<Integer> set, int value) {
		if (!set.remove(value)) set.add(value);
	}

	private int label(GuiGraphicsExtractor g, String text, int x, int y) {
		g.text(font, text, x, y, TEXT_2, false);
		return y + 11;
	}

	private int specField(GuiGraphicsExtractor g, String key, int x, int y, int w) {
		EditBox b = specBoxes.get(key);
		fieldBox(g, x, y, w, b);
		b.setVisible(y >= formTop && y + FIELD_H <= formBottom);
		return y + FIELD_H;
	}

	/** Colour squares, 8 per row. {@code on} and {@code click} get the square's index. */
	private int swatches(GuiGraphicsExtractor g, int mx, int my, int x, int y, List<Integer> rgbs, String id,
						 IntPredicate on, IntConsumer click) {
		for (int i = 0; i < rgbs.size(); i++) {
			int sx = x + (i % 8) * 13, sy = y + (i / 8) * 13, index = i;
			g.fill(sx, sy, sx + 10, sy + 10, 0xFF000000 | rgbs.get(i));
			if (on.test(i)) frame(g, sx - 1, sy - 1, 12, 12, 0xFFFFFFFF);
			else frame(g, sx, sy, 10, 10, in(mx, my, sx, sy, 10, 10) ? TEXT_2 : FIELD_LINE);
			hits.add(new Hit(sx - 1, sy - 1, 12, 12, id + ":" + i, shift -> click.accept(index)));
		}
		return y + ((rgbs.size() + 7) / 8) * 13;
	}

	private int addLink(GuiGraphicsExtractor g, int mx, int my, int x, int y, String label, String id, Runnable action) {
		int lw = font.width(label);
		g.text(font, label, x, y, in(mx, my, x, y - 2, lw, 12) ? TEXT : TEXT_3, false);
		hits.add(new Hit(x, y - 2, lw, 12, id, shift -> action.run()));
		return y + 12;
	}

	private void drawDropdown(GuiGraphicsExtractor g, int mx, int my) {
		dropRows = 0;
		if (!dropdownOpen()) return;
		dropRows = Math.min(MAX_DROP, sugs.size());
		sugSel = clamp(sugSel, 0, sugs.size() - 1);
		if (sugSel < sugTop) sugTop = sugSel;
		if (sugSel >= sugTop + dropRows) sugTop = sugSel - dropRows + 1;
		sugTop = clamp(sugTop, 0, Math.max(0, sugs.size() - dropRows));

		dropX = sugFor.getX() - 4;
		dropW = sugFor.getWidth() + 8;
		int h = dropRows * 12 + 2;
		dropY = sugFor.getY() + 12;
		if (dropY + h > height - 4) dropY = sugFor.getY() - 6 - h;
		g.fill(dropX, dropY, dropX + dropW, dropY + h, DROP);
		frame(g, dropX, dropY, dropW, h, FIELD_LINE);
		for (int i = 0; i < dropRows; i++) {
			int idx = sugTop + i, ry = dropY + 1 + i * 12;
			if (idx == sugSel) g.fill(dropX + 1, ry, dropX + dropW - 1, ry + 12, SEL_BG);
			else if (in(mx, my, dropX, ry, dropW, 12)) g.fill(dropX + 1, ry, dropX + dropW - 1, ry + 12, HOVER);
			g.text(font, font.plainSubstrByWidth(sugs.get(idx).text(), dropW - 10), dropX + 5, ry + 2, idx == sugSel ? SEL_TEXT : TEXT, false);
		}
		if (sugs.size() > dropRows) scrollbar(g, dropX + dropW - 3, dropY + 1, h - 2, sugs.size(), dropRows, sugTop);
	}

	/** Fades the last confirmation out with an ease-out curve. Returns false when nothing is showing. */
	private boolean drawFlash(GuiGraphicsExtractor g, int x, int y) {
		if (flashText == null) return false;
		float t = (Util.getMillis() - flashAt) / (float) FLASH_MS;
		if (t >= 1) {
			flashText = null;
			return false;
		}
		// Hold fully visible for the first 60%, then fade quickly: the system is answering, keep it snappy.
		float fade = t < 0.6f ? 0 : (t - 0.6f) / 0.4f;
		float alpha = (float) Math.pow(1 - fade, 3);
		int a = Math.round(alpha * 255);
		if (a < 8) return true;
		g.text(font, flashText, x, y, (a << 24) | (flashColor & 0xFFFFFF), false);
		return true;
	}

	private String parseHint() {
		String command = form.command();
		if (command.equals(checkedCommand)) return parseHint;
		checkedCommand = command;
		parseHint = null;
		if (!form.missing().isEmpty()) return null;
		// Advisory only: the client can't judge every permission-gated argument, so never block Run on it.
		ParseResults<ClientSuggestionProvider> p = dispatcher.parse(command, source);
		if (!p.getExceptions().isEmpty()) parseHint = p.getExceptions().values().iterator().next().getMessage();
		else if (p.getReader().canRead()) parseHint = "Server may not understand: " + p.getReader().getRemaining();
		else if (p.getContext().getCommand() == null) parseHint = "This command isn't complete yet";
		return parseHint;
	}

	private void hideFormBoxes() {
		boxes.values().forEach(b -> b.setVisible(false));
		specBoxes.values().forEach(b -> b.setVisible(false));
		if (nameBox != null) nameBox.setVisible(false);
	}

	private void fieldBox(GuiGraphicsExtractor g, int x, int y, int w, EditBox box) {
		g.fill(x, y, x + w, y + FIELD_H, FIELD);
		frame(g, x, y, w, FIELD_H, box.isFocused() ? FOCUS : FIELD_LINE);
		box.setX(x + 5);
		box.setY(y + 4);
		box.setWidth(w - 10);
	}

	private void row(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean selected, int mx, int my) {
		if (selected) g.fill(x, y, x + w, y + h, SEL_BG);
		else if (in(mx, my, x, y, w, h)) g.fill(x, y, x + w, y + h, HOVER);
	}

	private void button(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, String label, boolean primary,
						String id, Consumer<Boolean> onClick) {
		boolean down = id.equals(pressed), hover = in(mx, my, x, y, w, h);
		int sink = down ? 1 : 0;
		if (primary) g.fill(x, y + sink, x + w, y + h + sink, down ? ACCENT_PRESS : ACCENT);
		else {
			if (hover) g.fill(x, y + sink, x + w, y + h + sink, HOVER);
			frame(g, x, y + sink, w, h, FIELD_LINE);
		}
		g.centeredText(font, label, x + w / 2, y + 4 + sink, primary ? 0xFFFFFFFF : TEXT);
		hits.add(new Hit(x, y, w, h, id, onClick));
	}

	private void iconButton(GuiGraphicsExtractor g, int mx, int my, int x, int y, String icon, String id, Consumer<Boolean> onClick) {
		boolean hover = in(mx, my, x, y, 14, 14);
		if (hover) g.fill(x, y, x + 14, y + 14, HOVER);
		g.centeredText(font, icon, x + 7, y + 3 + (id.equals(pressed) ? 1 : 0), hover ? TEXT : TEXT_3);
		hits.add(new Hit(x, y, 14, 14, id, onClick));
	}

	private void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int total, int visible, int offset) {
		if (total <= visible) return;
		int barH = Math.max(8, h * visible / total);
		int barY = y + (h - barH) * offset / Math.max(1, total - visible);
		g.fill(x, barY, x + 2, barY + barH, FIELD_LINE);
	}

	private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y + 1, x + 1, y + h - 1, color);
		g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
	}

	private static boolean in(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	// ---------- input ----------

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (dropRows > 0 && in(e.x(), e.y(), dropX, dropY, dropW, dropRows * 12 + 2)) {
			int idx = sugTop + (int) ((e.y() - dropY - 1) / 12);
			if (idx >= 0 && idx < sugs.size()) acceptSuggestion(idx);
			return true;
		}
		if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit hit = hits.get(i);
				if (hit.contains(e.x(), e.y())) {
					pressed = hit.id();
					setFocused(null);
					hit.onClick().accept(e.hasShiftDown());
					return true;
				}
			}
		}
		if (super.mouseClicked(e, doubleClick)) return true;
		setFocused(null);
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		pressed = null;
		return super.mouseReleased(e);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		int step = sy > 0 ? -1 : 1;
		if (dropRows > 0 && in(mx, my, dropX, dropY, dropW, dropRows * 12 + 2)) {
			sugTop = clamp(sugTop + step, 0, Math.max(0, sugs.size() - dropRows));
			sugSel = clamp(sugSel, sugTop, sugTop + dropRows - 1);
			return true;
		}
		if (in(mx, my, listX, listTop, listW + 4, listBottom - listTop)) {
			listScroll += step;
			return true;
		}
		if (form != null && in(mx, my, formX, formTop, formW, formBottom - formTop)) {
			formScroll = clamp(formScroll + step * 12, 0, maxFormScroll);
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int key = e.key();
		boolean enter = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
		if (dropdownOpen()) {
			if (key == InputConstants.KEY_UP) { sugSel = Math.max(0, sugSel - 1); return true; }
			if (key == InputConstants.KEY_DOWN) { sugSel = Math.min(sugs.size() - 1, sugSel + 1); return true; }
			if (key == InputConstants.KEY_TAB || enter) { acceptSuggestion(sugSel); return true; }
			if (key == InputConstants.KEY_ESCAPE) { sugDismissed = true; return true; }
		}
		if (naming) {
			if (enter) { savePreset(); return true; }
			if (key == InputConstants.KEY_ESCAPE) { cancelNaming(); return true; }
		}
		if (enter) {
			if (getFocused() == search && !search.getValue().isBlank()) {
				var list = listed();
				if (!list.isEmpty()) openForm(list.getFirst());
				return true;
			}
			if (form != null && !savedView()) {
				runForm(e.hasShiftDown());
				return true;
			}
		}
		if (key == InputConstants.KEY_DOWN && getFocused() == search && !search.getValue().isBlank()) {
			var list = listed();
			if (!list.isEmpty()) openForm(list.getFirst());
			return true;
		}
		return super.keyPressed(e);
	}
}
