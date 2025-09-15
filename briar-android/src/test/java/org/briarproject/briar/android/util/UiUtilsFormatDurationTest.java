package org.briarproject.briar.android.util;

import android.content.Context;
import android.content.res.Resources;

import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.R;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Test;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.briarproject.briar.android.util.UiUtils.formatDuration;

public class UiUtilsFormatDurationTest extends BrambleMockTestCase {

	private final Context ctx;
	private final Resources r;
	private final int strMinutes = R.plurals.duration_minutes;
	private final int strHours = R.plurals.duration_hours;
	private final int strDays = R.plurals.duration_days;

	public UiUtilsFormatDurationTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		ctx = context.mock(Context.class);
		r = context.mock(Resources.class);
	}

	@Test
	public void testOneMinute() {
		expectMinuteString(1);
		formatDuration(ctx, MINUTES.toMillis(1));
	}

	@Test
	public void testOneHour() {
		expectHourString(1);
		formatDuration(ctx, HOURS.toMillis(1));
	}

	@Test
	public void testOneDay() {
		expectDayString(1);
		formatDuration(ctx, DAYS.toMillis(1));
	}

	@Test
	public void test10Seconds() {
		// capped to 1min
		expectMinuteString(1);
		formatDuration(ctx, SECONDS.toMillis(10));
	}

	@Test
	public void test100Seconds() {
		expectMinuteString(2);
		formatDuration(ctx, SECONDS.toMillis(100));
	}

	@Test
	public void test2Minutes() {
		expectMinuteString(2);
		formatDuration(ctx, MINUTES.toMillis(2));
	}

	@Test
	public void test10Minutes() {
		expectMinuteString(10);
		formatDuration(ctx, MINUTES.toMillis(10));
	}

	@Test
	public void test130Minutes() {
		expectHourString(2);
		expectMinuteString(10);
		formatDuration(ctx, MINUTES.toMillis(130));
	}

	@Test
	public void test13Hours() {
		expectHourString(13);
		formatDuration(ctx, HOURS.toMillis(13));
	}

	@Test
	public void testSevenDays() {
		expectDayString(7);
		formatDuration(ctx, DAYS.toMillis(7));
	}

	@Test
	public void testSevenDays2Hours() {
		expectDayString(7);
		expectHourString(2);
		formatDuration(ctx, DAYS.toMillis(7) + HOURS.toMillis(2));
	}

	@Test
	public void testSevenDays20Minutes() {
		expectDayString(7);
		formatDuration(ctx, DAYS.toMillis(7) + MINUTES.toMillis(20));
	}

	@Test
	public void testSevenDays40Minutes() {
		expectDayString(7);
		expectMinuteString(40);
		formatDuration(ctx, DAYS.toMillis(7) + MINUTES.toMillis(40));
	}

	@Test
	public void testTwoDays11Hours() {
		expectDayString(2);
		expectHourString(11);
		formatDuration(ctx, DAYS.toMillis(2) + HOURS.toMillis(11));
	}

	@Test
	public void testTwoDays12Hours() {
		expectDayString(2);
		expectHourString(12);
		formatDuration(ctx, DAYS.toMillis(2) + HOURS.toMillis(12));
	}

	@Test
	public void testTwoDays13Hours() {
		expectDayString(2);
		expectHourString(13);
		formatDuration(ctx, DAYS.toMillis(2) + HOURS.toMillis(13));
	}

	@Test
	public void test7Days23Hours55Minutes() {
		expectDayString(7);
		expectHourString(23);
		expectMinuteString(55);
		formatDuration(ctx,
				DAYS.toMillis(7) + HOURS.toMillis(23) + MINUTES.toMillis(55));
	}

	private void expectMinuteString(int minutes) {
		context.checking(new Expectations() {{
			oneOf(ctx).getResources();
			will(returnValue(r));
			oneOf(r).getQuantityString(strMinutes, minutes, minutes);
		}});
	}

	private void expectHourString(int hours) {
		context.checking(new Expectations() {{
			oneOf(ctx).getResources();
			will(returnValue(r));
			oneOf(r).getQuantityString(strHours, hours, hours);
		}});
	}

	private void expectDayString(int days) {
		context.checking(new Expectations() {{
			oneOf(ctx).getResources();
			will(returnValue(r));
			oneOf(r).getQuantityString(strDays, days, days);
		}});
	}

}
