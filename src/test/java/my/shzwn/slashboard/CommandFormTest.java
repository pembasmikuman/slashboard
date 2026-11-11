package my.shzwn.slashboard;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.*;

class CommandFormTest {
	private final CommandDispatcher<Object> d = new CommandDispatcher<>();

	{
		d.register(literal("enchant").then(argument("targets", StringArgumentType.word())
			.then(argument("enchantment", StringArgumentType.word()).executes(c -> 1)
				.then(argument("level", IntegerArgumentType.integer(0, 255)).executes(c -> 1)))));
		d.register(literal("time")
			.then(literal("set")
				.then(literal("day").executes(c -> 1))
				.then(argument("time", IntegerArgumentType.integer()).executes(c -> 1)))
			.then(literal("query").then(literal("daytime").executes(c -> 1))));
		LiteralCommandNode<Object> teleport = d.register(literal("teleport")
			.then(argument("destination", StringArgumentType.word()).executes(c -> 1)));
		d.register(literal("tp").redirect(teleport));
		d.register(literal("execute").then(literal("run").redirect(d.getRoot())));
		var rules = literal("gamerule");
		for (int i = 0; i < 10; i++) rules.then(literal("rule" + i).executes(c -> 1));
		d.register(rules);
		d.register(literal("kill").executes(c -> 1).then(argument("targets", StringArgumentType.word()).executes(c -> 1)));
	}

	private CommandForm<Object> form(String name) {
		return new CommandForm<>((LiteralCommandNode<Object>) d.getRoot().getChild(name));
	}

	private CommandNode<Object> node(String... path) {
		CommandNode<Object> n = d.getRoot();
		for (String p : path) n = n.getChild(p);
		return n;
	}

	@Test
	void enchantHasTwoRequiredFieldsThenOptionalLevel() {
		var f = form("enchant");
		assertEquals(List.of("targets", "enchantment"), f.missing());
		assertEquals("enchant <targets> <enchantment>", f.command());

		f.setValue(node("enchant", "targets"), "@s");
		f.setValue(node("enchant", "targets", "enchantment"), "minecraft:sharpness");
		assertTrue(f.missing().isEmpty());
		assertEquals(3, f.steps().size());
		assertTrue(f.steps().get(2).optional(), "level comes after a runnable node");
		assertEquals("enchant @s minecraft:sharpness", f.command());

		f.setValue(node("enchant", "targets", "enchantment", "level"), " 5 ");
		assertEquals("enchant @s minecraft:sharpness 5", f.command());
		assertEquals("enchant @s minecraft:sharpness ", f.prefixBefore(f.steps().get(2)));
	}

	@Test
	void literalBranchesBecomeChoicesAndChangeWhatFollows() {
		var f = form("time");
		var first = (CommandForm.Choice<?>) f.steps().getFirst();
		assertEquals(0, first.selected(), "small required choice defaults to the first option");
		assertTrue(first.useChips());
		assertEquals("time set day", f.command());

		// pick the <time> argument branch under "set": it needs a value
		f.choose(node("time", "set"), 1);
		assertEquals(List.of("time"), f.missing());
		f.setValue(node("time", "set", "time"), "6000");
		assertEquals("time set 6000", f.command());

		f.choose(node("time"), 1);
		assertEquals("time query daytime", f.command());
	}

	@Test
	void aliasFollowsRedirectButKeepsTypedName() {
		var f = form("tp");
		assertInstanceOf(CommandForm.Field.class, f.steps().getFirst());
		f.setValue(node("teleport", "destination"), "Steve");
		assertEquals("tp Steve", f.command());
	}

	@Test
	void redirectToRootFallsBackToRawText() {
		var f = form("execute");
		f.choose(node("execute"), 0);
		assertInstanceOf(CommandForm.Raw.class, f.steps().getLast());
		assertEquals(List.of("command"), f.missing());
		f.setRaw("say hi");
		assertEquals("execute run say hi", f.command());
	}

	@Test
	void bigChoiceUsesPickBoxAndStartsEmpty() {
		var f = form("gamerule");
		var c = (CommandForm.Choice<?>) f.steps().getFirst();
		assertFalse(c.useChips());
		assertEquals(-1, c.selected());
		assertEquals(List.of("gamerule"), f.missing());
		f.choose(node("gamerule"), 3);
		assertEquals("gamerule rule3", f.command());
	}

	@Test
	void runnableRootMakesFirstFieldOptional() {
		var f = form("kill");
		assertTrue(f.missing().isEmpty());
		assertEquals("kill", f.command());
	}
}
