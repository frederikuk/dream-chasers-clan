/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

final class ApiTimes
{
	private ApiTimes()
	{
	}

	/** ISO 8601 instant, or null if missing or malformed. */
	static Instant parse(String iso)
	{
		if (iso == null || iso.isEmpty())
		{
			return null;
		}
		try
		{
			return Instant.parse(iso);
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	/** YYYY-MM-DD, or null if missing or malformed. */
	static LocalDate parseDate(String date)
	{
		if (date == null || date.isEmpty())
		{
			return null;
		}
		try
		{
			return LocalDate.parse(date);
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}
}
