package my.shzwn.slashboard;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one command's Brigadier tree into a list of form steps and back into a command string.
 * Only depends on Brigadier so it can be tested without Minecraft.
 *
 * The form is walked fresh every time {@link #steps()} is called, following the user's current
 * choices, so picking a different branch (say {@code time add} instead of {@code time set})
 * changes the fields that follow. Values are keyed by tree node, so they survive re-walks.
 */
public final class CommandForm<S> {
	/** Choices with more options than this show as a pick box instead of chips. */
	public static final int MAX_CHIPS = 6;
	private static final int MAX_DEPTH = 64;

	public sealed interface Step permits Choice, Field, Raw {
		boolean optional();
	}

	/**
	 * Pick one child of {@code parent}. {@code selected} is -1 when nothing is picked:
	 * for an optional choice that means "stop here", for a required one it means missing.
	 */
	public record Choice<S>(CommandNode<S> parent, List<CommandNode<S>> options, int selected, boolean optional) implements Step {
		public boolean useChips() {
			return options.size() <= MAX_CHIPS;
		}
	}

	/** Type a value for an argument node. */
	public record Field<S>(ArgumentCommandNode<S, ?> node, boolean optional) implements Step {}

	/** The tree redirects back to the top (like {@code execute ... run}), so the rest is free text. */
	public record Raw(boolean optional) implements Step {}

	private final String label;
	private final CommandNode<S> start;
	private final Map<CommandNode<S>, Integer> choices = new HashMap<>();
	private final Map<CommandNode<S>, String> values = new HashMap<>();
	private String raw = "";

	public CommandForm(LiteralCommandNode<S> root) {
		this.label = root.getLiteral();
		// Aliases like /tp are an empty node redirecting to /teleport: walk the real one.
		this.start = root.getRedirect() != null && root.getChildren().isEmpty() && root.getRedirect() instanceof LiteralCommandNode<S>
			? root.getRedirect()
			: root;
	}

	public String label() {
		return label;
	}

	public String value(CommandNode<S> node) {
		return values.getOrDefault(node, "");
	}

	public void setValue(CommandNode<S> node, String value) {
		values.put(node, value.strip());
	}

	public String raw() {
		return raw;
	}

	public void setRaw(String raw) {
		this.raw = raw.strip();
	}

	/** Select option {@code index} of the choice under {@code parent}, or -1 to clear it. */
	public void choose(CommandNode<S> parent, int index) {
		choices.put(parent, index);
	}

	public List<Step> steps() {
		List<Step> steps = new ArrayList<>();
		CommandNode<S> node = start;
		for (int depth = 0; depth < MAX_DEPTH; depth++) {
			boolean optional = node.getCommand() != null;
			if (node.getRedirect() != null) {
				steps.add(new Raw(optional));
				return steps;
			}
			List<CommandNode<S>> children = new ArrayList<>(node.getChildren());
			if (children.isEmpty()) return steps;

			if (children.size() == 1 && children.getFirst() instanceof ArgumentCommandNode<S, ?> arg) {
				steps.add(new Field<>(arg, optional));
				// An empty optional field ends the command; an empty required one still shows what follows.
				if (optional && value(arg).isEmpty()) return steps;
				node = arg;
				continue;
			}

			int fallback = optional || children.size() > MAX_CHIPS ? -1 : 0;
			int selected = choices.getOrDefault(node, fallback);
			if (selected >= children.size()) selected = fallback;
			steps.add(new Choice<>(node, children, selected, optional));
			if (selected < 0) return steps;
			node = children.get(selected);
			if (node instanceof ArgumentCommandNode<S, ?> arg) {
				// The chip only picked which argument branch; the value still needs a field.
				steps.add(new Field<>(arg, false));
			}
		}
		return steps;
	}

	/** The command without the leading slash. Missing required values show as {@code <name>}. */
	public String command() {
		return build(null);
	}

	/** Everything typed before {@code field}, ending in a space, for asking the dispatcher for suggestions. */
	public String prefixBefore(Step field) {
		return build(field) + " ";
	}

	private String build(Step stopAt) {
		StringBuilder out = new StringBuilder(label);
		for (Step step : steps()) {
			if (step.equals(stopAt)) break;
			String part = switch (step) {
				case Choice<?> c -> c.selected() < 0
					? (c.optional() ? null : "<" + c.parent().getName() + ">")
					: c.options().get(c.selected()) instanceof LiteralCommandNode<?> lit ? lit.getLiteral() : null;
				case Field<?> f -> {
					String v = values.getOrDefault(f.node(), "");
					yield v.isEmpty() ? (f.optional() ? null : "<" + f.node().getName() + ">") : v;
				}
				case Raw r -> raw.isEmpty() ? (r.optional() ? null : "<command>") : raw;
			};
			if (part != null) out.append(' ').append(part);
		}
		return out.toString();
	}

	/** Names of required parts that are still empty. Empty list means the command is ready to run. */
	public List<String> missing() {
		List<String> out = new ArrayList<>();
		for (Step step : steps()) {
			switch (step) {
				case Choice<?> c -> { if (c.selected() < 0 && !c.optional()) out.add(c.parent().getName()); }
				case Field<?> f -> { if (!f.optional() && values.getOrDefault(f.node(), "").isEmpty()) out.add(f.node().getName()); }
				case Raw r -> { if (!r.optional() && raw.isEmpty()) out.add("command"); }
			}
		}
		return out;
	}
}
