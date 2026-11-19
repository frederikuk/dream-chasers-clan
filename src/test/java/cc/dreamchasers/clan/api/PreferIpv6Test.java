/*
 * Copyright (c) 2026, Frederik Bowling
 * All rights reserved.
 *
 * Licensed under the BSD 2-Clause License; see the LICENSE file in the
 * repository root.
 */
package cc.dreamchasers.clan.api;

import static org.junit.Assert.assertEquals;
import java.net.InetAddress;
import java.util.List;
import org.junit.Test;

/**
 * The sign-in start goes over IPv6 when there is one, so the site sees the same
 * network as the browser approving it and doesn't ask for the code (19 Nov 2026).
 */
public class PreferIpv6Test
{
	@Test
	public void ipv6AddressesComeFirstAndIpv4StaysAsTheFallback() throws Exception
	{
		InetAddress v4a = InetAddress.getByAddress("site", new byte[]{104, 21, 0, 1});
		InetAddress v4b = InetAddress.getByAddress("site", new byte[]{(byte) 172, 67, 0, 1});
		InetAddress v6a = InetAddress.getByAddress("site", new byte[]{0x26, 0x06, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x68, 0x15, 0, 1});
		InetAddress v6b = InetAddress.getByAddress("site", new byte[]{0x26, 0x06, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xac, 0x43, 0, 1});
		List<InetAddress> got = DrmApiClient.preferIpv6(host -> List.of(v4a, v6a, v4b, v6b)).lookup("dream-chasers.cc");
		assertEquals(List.of(v6a, v6b, v4a, v4b), got);
		assertEquals("no IPv6: unchanged", List.of(v4a, v4b),
			DrmApiClient.preferIpv6(host -> List.of(v4a, v4b)).lookup("dream-chasers.cc"));
	}
}
