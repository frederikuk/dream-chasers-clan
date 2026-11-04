/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Short, human times for the panel, in the player's own time zone. */
public final class TimeText
{
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
	private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

	private TimeText()
	{
	}

	/** "Mon 2 Nov, 19:00" in {@code zone}. */
	public static String when(Instant instant, ZoneId zone)
	{
		return instant == null ? "" : WHEN.format(instant.atZone(zone));
	}

	/** "2 Nov" in {@code currentYear}, "2 Nov 2025" otherwise. */
	public static String day(LocalDate date, int currentYear)
	{
		if (date == null)
		{
			return "";
		}
		return date.getYear() == currentYear ? DAY.format(date) : DAY_YEAR.format(date);
	}

	/** "2 Nov" for an instant in {@code zone}. */
	public static String day(Instant instant, ZoneId zone)
	{
		return instant == null ? "" : DAY.format(instant.atZone(zone));
	}

	/**
	 * A rough length: "3d 4h", "2h 15m", "45m", "under a minute". Negative
	 * durations are treated as zero.
	 */
	public static String span(Duration d)
	{
		long minutes = Math.max(0, d.toMinutes());
		if (minutes < 1)
		{
			return "under a minute";
		}
		long days = minutes / (60 * 24);
		long hours = (minutes / 60) % 24;
		long mins = minutes % 60;
		if (days > 0)
		{
			return hours > 0 ? days + "d " + hours + "h" : days + "d";
		}
		if (hours > 0)
		{
			return mins > 0 ? hours + "h " + mins + "m" : hours + "h";
		}
		return mins + "m";
	}

	/** "in 2h 15m", or "" when {@code target} is null. */
	public static String until(Instant now, Instant target)
	{
		return target == null ? "" : "in " + span(Duration.between(now, target));
	}
}
