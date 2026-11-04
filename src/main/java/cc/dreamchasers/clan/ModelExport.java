/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan;

import java.util.Arrays;
import net.runelite.api.Model;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import net.runelite.api.gameval.AnimationID;

/**
 * The local player's model, copied on the client thread, then written as
 * the OBJ that the clan splash renderer reads.
 *
 * <p>The format matches RuneLite's Model-Dumper plugin (Bram91/Model-Dumper,
 * BSD-2, {@code OBJExporter}): integer vertices with Y and Z negated, so up is
 * +Y, and 1-based triangle faces. Unlike Model-Dumper it skips hidden faces
 * (colour 3 of -2).
 *
 * <p>Colours ride in
 * comment lines, so every older reader (the site's upload check, the splash
 * renderer, Model-Dumper tools) still reads the file unchanged: {@code # c <hsl>}
 * comes before a run of faces and gives their colour as the game's packed
 * 16-bit HSL (6 bits hue, 3 saturation, 7 lightness), as lit in game, like
 * {@code usemtl}. A smooth-shaded face gets its first corner's hue and
 * saturation and the average lightness of its three corners.
 */
final class ModelExport
{
	/** The site refuses anything bigger; a player model is about 60 KB. */
	static final int MAX_BYTES = 200 * 1024;
	/** A face whose third colour is this is never drawn. */
	private static final int HIDDEN_FACE = -2;

	private final float[] x;
	private final float[] y;
	private final float[] z;
	private final int[] f1;
	private final int[] f2;
	private final int[] f3;
	private final int[] colors1;
	private final int[] colors2;
	private final int[] colors3;

	/** Without colours: only hidden faces are taken from {@code colors3}. */
	ModelExport(float[] x, float[] y, float[] z, int[] f1, int[] f2, int[] f3, int[] colors3)
	{
		this(x, y, z, f1, f2, f3, null, null, colors3);
	}

	ModelExport(float[] x, float[] y, float[] z, int[] f1, int[] f2, int[] f3,
		int[] colors1, int[] colors2, int[] colors3)
	{
		this.colors1 = colors1;
		this.colors2 = colors2;
		this.x = x;
		this.y = y;
		this.z = z;
		this.f1 = f1;
		this.f2 = f2;
		this.f3 = f3;
		this.colors3 = colors3;
	}

	/**
	 * Copies what the OBJ needs out of {@code m}. Call on the client thread:
	 * the game reuses a model's arrays from frame to frame.
	 */
	static ModelExport copyOf(Model m)
	{
		int vc = m.getVerticesCount();
		int fc = m.getFaceCount();
		int[] c1 = m.getFaceColors1();
		int[] c2 = m.getFaceColors2();
		int[] c3 = m.getFaceColors3();
		boolean colours = c1 != null && c2 != null && c3 != null;
		return new ModelExport(
			Arrays.copyOf(m.getVerticesX(), vc), Arrays.copyOf(m.getVerticesY(), vc), Arrays.copyOf(m.getVerticesZ(), vc),
			Arrays.copyOf(m.getFaceIndices1(), fc), Arrays.copyOf(m.getFaceIndices2(), fc), Arrays.copyOf(m.getFaceIndices3(), fc),
			colours ? Arrays.copyOf(c1, fc) : null, colours ? Arrays.copyOf(c2, fc) : null,
			c3 == null ? null : Arrays.copyOf(c3, fc));
	}

	/**
	 * Why this pose can't be used, or null when it can. Idle only: no
	 * animation, and the idle pose (not walking, running or turning). The
	 * splash rig lifts both arms to hold the heart, so the hands must be
	 * empty: anything held keeps its arm down, even a sword or axe that
	 * leaves the stance alone. It also needs the standard unarmed stance;
	 * bows, staves and two-handers swap it for one with staggered feet.
	 */
	static String poseProblem(int animation, int pose, int idlePose, boolean holdingSomething)
	{
		if (animation != -1 || pose != idlePose)
		{
			return "Stand still and try again.";
		}
		if (holdingSomething)
		{
			return "Take off your weapon and shield, then press again.";
		}
		if (idlePose != AnimationID.HUMAN_READY)
		{
			return "Your weapon changes how you stand. Take it off, then press again.";
		}
		return null;
	}

	/**
	 * True when the weapon or shield slot shows an item. An unknown look counts
	 * as empty; the renderer refuses held items itself as a backstop.
	 */
	static boolean holdingSomething(PlayerComposition look)
	{
		return look != null && holdingSomething(look.getEquipmentId(KitType.WEAPON), look.getEquipmentId(KitType.SHIELD));
	}

	/** {@code -1} is an empty slot. */
	static boolean holdingSomething(int weaponId, int shieldId)
	{
		return weaponId != -1 || shieldId != -1;
	}

	/**
	 * One face's colour as packed HSL. Colour 3 of -1 is a flat face, coloured
	 * by colour 1; otherwise the corners differ only in lightness, so keep the
	 * first corner's hue and saturation and average the lightness.
	 */
	static int faceColour(int c1, int c2, int c3)
	{
		if (c3 == -1)
		{
			return c1 & 0xffff;
		}
		int lum = ((c1 & 127) + (c2 & 127) + (c3 & 127)) / 3;
		return (c1 & 0xff80) | lum;
	}

	int vertexCount()
	{
		return x.length;
	}

	/** The OBJ text. {@code name} goes on the {@code o} line. */
	String toObj(String name)
	{
		StringBuilder sb = new StringBuilder(64 * 1024);
		sb.append("# DRM clan plugin model export\n");
		sb.append("o ").append(name == null ? "player" : name.replaceAll("[\\r\\n]", " ")).append('\n');
		for (int i = 0; i < x.length; i++)
		{
			// Model-Dumper: Y and Z are flipped
			sb.append("v ").append((int) x[i]).append(' ').append((int) -y[i]).append(' ').append((int) -z[i]).append('\n');
		}
		boolean colours = colors1 != null && colors2 != null && colors3 != null;
		int last = -1;
		for (int i = 0; i < f1.length; i++)
		{
			if (colors3 != null && colors3[i] == HIDDEN_FACE)
			{
				continue;
			}
			if (colours)
			{
				int c = faceColour(colors1[i], colors2[i], colors3[i]);
				if (c != last)
				{
					sb.append("# c ").append(c).append('\n');
					last = c;
				}
			}
			sb.append("f ").append(f1[i] + 1).append(' ').append(f2[i] + 1).append(' ').append(f3[i] + 1).append('\n');
		}
		return sb.toString();
	}
}
