package my.shzwn.slashboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemSpecTest {
	@Test
	void emptySpecAddsNothing() {
		assertEquals("", new ItemSpec().components(true));
	}

	@Test
	void customSword() {
		ItemSpec s = new ItemSpec();
		s.name = "Excalibur \"the\" Blade";
		s.nameColor = "gold";
		s.bold = true;
		s.lore.add("Forged in the Nether");
		ItemSpec.Ench sharp = new ItemSpec.Ench();
		sharp.id = "minecraft:sharpness";
		sharp.level = "10";
		ItemSpec.Ench blank = new ItemSpec.Ench();
		ItemSpec.Ench junk = new ItemSpec.Ench();
		junk.id = "looting";
		junk.level = "lots";
		s.enchants.addAll(java.util.List.of(sharp, blank, junk));
		s.unbreakable = true;
		s.glow = true;
		assertEquals("[custom_name={text:\"Excalibur \\\"the\\\" Blade\",italic:false,color:\"gold\",bold:true},"
			+ "lore=[\"Forged in the Nether\"],enchantments={\"minecraft:sharpness\":10,\"looting\":1},"
			+ "unbreakable={},enchantment_glint_override=true]", s.components(false));
	}

	@Test
	void fireworkOnlyOnRockets() {
		ItemSpec s = new ItemSpec();
		s.flight = 2;
		ItemSpec.Explosion e = new ItemSpec.Explosion();
		e.shape = "star";
		e.colors.add(14602026);
		e.colors.add(2437522);
		e.fade.add(6719955);
		e.trail = true;
		e.twinkle = true;
		s.explosions.add(e);
		assertEquals("[fireworks={flight_duration:2,explosions:[{shape:\"star\",colors:[I;14602026,2437522],"
			+ "fade_colors:[I;6719955],has_trail:true,has_twinkle:true}]}]", s.components(true));
		assertEquals("", s.components(false));
	}

	@Test
	void levelsAreClamped() {
		assertEquals(255, ItemSpec.level("9999"));
		assertEquals(1, ItemSpec.level("0"));
		assertEquals(1, ItemSpec.level(""));
	}

	@Test
	void baseDropsComponents() {
		assertEquals("diamond_sword", ItemSpec.baseOf("diamond_sword[unbreakable={}]"));
		assertEquals(true, ItemSpec.isRocket(ItemSpec.baseOf("minecraft:firework_rocket[fireworks={}]")));
	}
}
