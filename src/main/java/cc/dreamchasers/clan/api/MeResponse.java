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

/**
 * {@code GET /plugin/me}: who the stored token belongs to, and the names
 * verified to that Discord account (frozen ones left out; absent on older sites).
 */
@Data
public class MeResponse
{
	private int apiVersion;
	private String name;
	private List<String> verified;
}
