/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.pb;

import lombok.Value;
import lombok.With;

/**
 * One timed kill read from game messages. {@code seconds} is the new
 * personal best when {@code pb} is true; otherwise it is the standing personal
 * best the game quoted after a slower kill.
 */
@Value
@With
public class PbRecord
{
	String boss;
	double seconds;
	/** "1" for solo, "3", "24+" or "11-15"; null when the game didn't say. */
	String teamSize;
	/** null when the game didn't give a count. */
	Integer kc;
	boolean pb;

	/** Theatre of Blood and Tombs of Amascut don't put the team size in chat; the plugin reads the party. */
	public boolean needsPartySize()
	{
		return teamSize == null && (boss.startsWith("Theatre of Blood") || boss.startsWith("Tombs of Amascut"));
	}

	/** The key the plugin remembers what it last sent under. */
	public String key()
	{
		return boss + "|" + (teamSize == null ? "" : teamSize);
	}
}
