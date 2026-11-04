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
 * The member's own splash entry: {@code GET /plugin/model}, and the answer to
 * an upload. The site wraps it as {@code {featured: {rsn, status, reason,
 * uploadedAt, renderedAt}}}, with {@code featured: null} when the member isn't
 * in. {@code status} is {@code queued}, {@code ok} or {@code rejected};
 * {@code reason} says why a model was rejected, for example "arms not found:
 * stand idle and try again".
 */
@Data
public class ModelStatusResponse
{
	@Data
	public static class Entry
	{
		private String rsn;
		private String status;
		private String reason;
		private String uploadedAt;
		private String renderedAt;
	}

	private Entry featured;
	private String note;

	public boolean isOptedIn()
	{
		return featured != null;
	}

	public String getStatus()
	{
		return featured == null ? null : featured.getStatus();
	}

	public String getReason()
	{
		return featured == null ? null : featured.getReason();
	}

	public String getUploadedAt()
	{
		return featured == null ? null : featured.getUploadedAt();
	}
}
