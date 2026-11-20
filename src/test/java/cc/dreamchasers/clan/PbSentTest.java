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
import static org.junit.Assert.assertTrue;
import cc.dreamchasers.clan.api.ApiResult;
import cc.dreamchasers.clan.api.SubmitResponse;
import cc.dreamchasers.clan.pb.PbRecord;
import java.time.Instant;
import org.junit.Test;

/**
 * A PB counts as sent only once the site took it (20 Nov 2026: a PB refused
 * while the member's sign-in was held never went again without a relog).
 */
public class PbSentTest
{
	private static final PbRecord STANDING = new PbRecord("Vorkath", 75.0, "1", 120, false);

	private static ApiResult<SubmitResponse> answer(ApiResult.Status status, int code)
	{
		return new ApiResult<>(status, status == ApiResult.Status.OK ? new SubmitResponse() : null, Instant.EPOCH, "x", code);
	}

	@Test
	public void aRefusedOrLostPbGoesAgainWithTheNextLine()
	{
		DreamChasersClanPlugin p = new DreamChasersClanPlugin();
		assertTrue(p.pbDue(STANDING));
		p.pbAnswered(STANDING, 0, 0, answer(ApiResult.Status.FORBIDDEN, 403));
		assertTrue("refused: tried again", p.pbDue(STANDING));
		p.pbAnswered(STANDING, 0, 0, answer(ApiResult.Status.ERROR, 429));
		assertTrue("rate limited: tried again", p.pbDue(STANDING));
		p.pbAnswered(STANDING, 0, 0, answer(ApiResult.Status.ERROR, 0));
		assertTrue("no answer: tried again", p.pbDue(STANDING));
		p.pbAnswered(STANDING, 0, 0, answer(ApiResult.Status.OK, 200));
		assertFalse("taken: once a session", p.pbDue(STANDING));
		assertTrue("a better time goes", p.pbDue(STANDING.withSeconds(70.0)));
		assertTrue("a new PB always goes", p.pbDue(STANDING.withPb(true)));
	}

	@Test
	public void anAnswerFromBeforeALogoutMarksNothing()
	{
		DreamChasersClanPlugin p = new DreamChasersClanPlugin();
		p.forgetPbsSent();     // the login screen: maybe another account next
		p.pbAnswered(STANDING, 0, 0, answer(ApiResult.Status.OK, 200));
		assertTrue(p.pbDue(STANDING));
		p.pbAnswered(STANDING, 1, 0, answer(ApiResult.Status.OK, 200));
		assertFalse(p.pbDue(STANDING));
	}

	@Test
	public void a401ForAnEarlierSignInKeepsTheNewToken()
	{
		// asking again for the same name revokes the previous held token at the site: a PB
		// sent under it can answer 401 after the new token is stored
		int[] forgot = {0};
		DreamChasersClanPlugin p = new DreamChasersClanPlugin()
		{
			@Override
			void forgetToken()
			{
				forgot[0]++;
			}
		};
		p.beginSignIn(false);
		int sent = p.signInGen;
		p.beginSignIn(false);
		p.pbAnswered(STANDING, 0, sent, answer(ApiResult.Status.UNAUTHORIZED, 401));
		assertEquals("the old token's 401: the new one stays", 0, forgot[0]);
		p.pbAnswered(STANDING, 0, p.signInGen, answer(ApiResult.Status.UNAUTHORIZED, 401));
		assertEquals("this token's own 401 still signs out", 1, forgot[0]);
		p.forgetToken(sent);
		assertEquals(1, forgot[0]);
	}

	@Test
	public void twoAnswersKeepTheBetterTime()
	{
		DreamChasersClanPlugin p = new DreamChasersClanPlugin();
		p.pbAnswered(STANDING.withSeconds(70.0), 0, 0, answer(ApiResult.Status.OK, 200));
		p.pbAnswered(STANDING, 0, 0, answer(ApiResult.Status.OK, 200));     // an older line's answer, later
		assertFalse(p.pbDue(STANDING.withSeconds(70.0)));
	}
}
