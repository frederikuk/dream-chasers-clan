/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import java.util.List;
import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /plugin/live/join}: this member's own stream slot. The push link
 * is a private credential, so it is kept in memory only and left out of
 * {@code toString()} (and so out of any log line).
 */
@Data
public class LiveJoinResponse
{
	private int slot;
	@ToString.Exclude
	private String pushUrl;
	private String voiceUrl;
	/** False until a producer puts this guest on air (new guests wait on hold). */
	private boolean onAir;
	private List<String> steps;
}
