/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import lombok.Data;

/**
 * The answer to {@code POST /plugin/logout}. The site always answers 200; it
 * only asks Wise Old Man to update the member when they are in a running clan
 * competition. Nothing is ever sent again for it.
 */
@Data
public class LogoutResponse
{
	private boolean ok;
}
