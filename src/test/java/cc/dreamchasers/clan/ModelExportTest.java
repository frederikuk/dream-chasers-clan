/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.nio.charset.StandardCharsets;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

public class ModelExportTest
{
	@Test
	public void writesModelDumperObjWithYAndZFlippedAndOneBasedFaces()
	{
		// RuneLite models have -y up; floats are truncated like Model-Dumper's (int) cast
		ModelExport m = new ModelExport(
			new float[]{-10f, 4.9f, 3f}, new float[]{-187f, -187f, 8f}, new float[]{20f, -22f, 0f},
			new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 1},
			new int[]{-1, 5});

		String obj = m.toObj("Maho z h");

		assertEquals("# DRM clan plugin model export\n"
			+ "o Maho z h\n"
			+ "v -10 187 -20\n"
			+ "v 4 187 22\n"
			+ "v 3 -8 0\n"
			+ "f 1 2 3\n"
			+ "f 1 3 2\n", obj);
	}

	@Test
	public void coloursRideInCommentLinesBeforeEachRunOfFaces()
	{
		// face 0 flat (colour 3 of -1), face 1 the same colour, face 2 hidden,
		// face 3 smooth: hue/saturation of corner 1, lightness averaged (10, 20, 33 -> 21)
		int red = (0 << 10) | (7 << 7);
		int blue = (43 << 10) | (5 << 7);
		ModelExport m = new ModelExport(
			new float[]{0, 1, 2}, new float[]{0, 0, 0}, new float[]{0, 0, 0},
			new int[]{0, 0, 0, 1}, new int[]{1, 2, 2, 2}, new int[]{2, 1, 1, 0},
			new int[]{red | 64, red | 64, 5, blue | 10}, new int[]{0, 0, 5, blue | 20}, new int[]{-1, -1, -2, blue | 33});

		String obj = m.toObj("x");

		assertEquals("# DRM clan plugin model export\n"
			+ "o x\n"
			+ "v 0 0 0\n"
			+ "v 1 0 0\n"
			+ "v 2 0 0\n"
			+ "# c " + (red | 64) + "\n"
			+ "f 1 2 3\n"
			+ "f 1 3 2\n"
			+ "# c " + (blue | 21) + "\n"
			+ "f 2 3 1\n", obj);
	}

	@Test
	public void everyLineIsStillOneTheOldReadersAccept()
	{
		// the site's upload check and the splash renderer take v, f, o and # lines only
		ModelExport m = new ModelExport(
			new float[]{0, 1, 2}, new float[]{0, -5, 0}, new float[]{0, 0, 3},
			new int[]{0, 1}, new int[]{1, 2}, new int[]{2, 0},
			new int[]{100, 70000}, new int[]{100, 3}, new int[]{-1, 9});
		for (String line : m.toObj("Maho z h").split("\n"))
		{
			assertTrue(line, line.matches("# .*|o .*|v -?\\d+ -?\\d+ -?\\d+|f \\d+ \\d+ \\d+"));
			if (line.startsWith("# c "))
			{
				int c = Integer.parseInt(line.substring(4));
				assertTrue("16-bit HSL", c >= 0 && c <= 0xffff);
			}
		}
	}

	@Test
	public void skipsHiddenFacesButKeepsEveryVertex()
	{
		ModelExport m = new ModelExport(
			new float[]{0, 1, 2}, new float[]{0, 0, 0}, new float[]{0, 0, 0},
			new int[]{0, 0, 1}, new int[]{1, 2, 2}, new int[]{2, 1, 0},
			new int[]{3, -2, -1});

		String obj = m.toObj("x");

		assertEquals(3, count(obj, "\nv "));
		assertEquals(2, count(obj, "\nf "));
		assertTrue(obj.contains("f 1 2 3\n"));
		assertTrue("the hidden face (index 1) is gone", !obj.contains("f 1 3 2\n"));
		assertTrue(obj.contains("f 2 3 1\n"));
	}

	@Test
	public void noColoursMeansNoFacesAreSkippedAndNewlinesCannotBreakTheName()
	{
		ModelExport m = new ModelExport(new float[]{0}, new float[]{0}, new float[]{0},
			new int[]{0}, new int[]{0}, new int[]{0}, null);

		String obj = m.toObj("a\nv 9 9 9");

		assertTrue(obj.startsWith("# DRM clan plugin model export\no a v 9 9 9\n"));
		assertEquals(1, count(obj, "\nf "));
	}

	@Test
	public void aPlayerSizedModelFitsTheUploadLimit()
	{
		// about the size of a real player (one player model: 1,492 vertices, 2,623 faces, 60 KB)
		int vc = 2000;
		int fc = 3500;
		float[] x = new float[vc];
		float[] y = new float[vc];
		float[] z = new float[vc];
		for (int i = 0; i < vc; i++)
		{
			x[i] = -100 + i % 200;
			y[i] = -(i % 330);
			z[i] = -100 + i % 200;
		}
		int[] a = new int[fc];
		int[] b = new int[fc];
		int[] c = new int[fc];
		for (int i = 0; i < fc; i++)
		{
			a[i] = i % vc;
			b[i] = (i + 1) % vc;
			c[i] = (i + 2) % vc;
		}
		String obj = new ModelExport(x, y, z, a, b, c, null).toObj("Twelve chars");
		assertTrue(obj.getBytes(StandardCharsets.UTF_8).length < ModelExport.MAX_BYTES);
	}

	private static int count(String s, String sub)
	{
		int n = 0;
		for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1))
		{
			n++;
		}
		return n;
	}

	@Test
	public void onlyEmptyHandsInTheStandardIdleStanceAreUsed()
	{
		String stand = "Stand still and try again.";
		String hands = "Take off your weapon and shield, then press again.";
		String weapon = "Your weapon changes how you stand. Take it off, then press again.";
		int ready = AnimationID.HUMAN_READY;
		// Empty hands, standing still
		assertNull(ModelExport.poseProblem(-1, ready, ready, false));
		// Moving or emoting comes first, whatever is held
		assertEquals(stand, ModelExport.poseProblem(-1, AnimationID.HUMAN_WALK_F, ready, false));
		assertEquals(stand, ModelExport.poseProblem(AnimationID.READY_CLUE_SCROLL, ready, ready, true));
		assertEquals(stand, ModelExport.poseProblem(-1, AnimationID.HUMAN_WALK_F, AnimationID.HUMAN_STAFFREADY, true));
		// An axe, sword or shield keeps the stance but holds an arm down
		assertEquals(hands, ModelExport.poseProblem(-1, ready, ready, true));
		assertEquals(hands, ModelExport.poseProblem(-1, AnimationID.HUMAN_DH_WEAPON_READY, AnimationID.HUMAN_DH_WEAPON_READY, true));
		// The stance check stays for an odd idle with empty hands
		assertEquals(weapon, ModelExport.poseProblem(-1, AnimationID.HUMAN_STAFFREADY, AnimationID.HUMAN_STAFFREADY, false));
	}

	@Test
	public void weaponOrShieldSlotCountsAsHolding()
	{
		assertFalse(ModelExport.holdingSomething(-1, -1));
		assertTrue(ModelExport.holdingSomething(ItemID.RUNE_AXE, -1));
		assertTrue(ModelExport.holdingSomething(-1, ItemID.RUNE_KITESHIELD));
		assertTrue(ModelExport.holdingSomething(ItemID.RUNE_SWORD, ItemID.RUNE_KITESHIELD));
		assertFalse(ModelExport.holdingSomething((net.runelite.api.PlayerComposition) null));
	}
}
