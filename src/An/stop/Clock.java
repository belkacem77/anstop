/***************************************************************************
 *   Copyright (C) 2009 by mj   										   *
 *   fakeacc.mj@gmail.com  												   *
 *   Portions of this file Copyright (C) 2010 Jeremy Monin jeremy@nand.net *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 ***************************************************************************/


package An.stop;


import java.text.NumberFormat;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

/**
 * Timer object and thread.
 *<P>
 * Has two modes (STOP and COUNTDOWN); its mode field is {@link #v}.
 * Has accessible fields for the current {@link #hour}, {@link #min}, {@link #sec}, {@link #dsec}.
 * Has a link to the {@link #parent} Anstop, and will sometimes read or set parent's text field contents.
 *<P>
 * Because of device power saving, there are methods to adjust the clock
 * when our app is paused/resumed: {@link #onAppPause()}, {@link #onAppResume()}.
 * Otherwise the counting would become inaccurate.
 *<P>
 * Has three states:
 *<UL>
 * <LI> Reset: This is the initial state.
 *        In STOP mode, the hour, minute, second are 0.
 *        In COUNTDOWN mode they're (h, m, s), copied from spinners when
 *        the user hits the "refresh" button.
 * <LI> Started: The clock is running.
 * <LI> Stopped: The clock is not currently running, but it's changed from
 *        the initial values.  That is, the clock is paused, and the user
 *        can start it to continue counting.
 *</UL>
 * You can examine the current state by reading {@link #isStarted} and {@link #wasStarted}.
 * To reset the clock again, and/or change the mode, call {@link #reset(int, int, int, int)}.
 *<P>
 * Keeping track of laps is done in {@link Anstop}, not in this object.
 */
public class Clock {

	/**
	 * Stopwatch Mode for {@link #v}.
	 * Same value as <tt>Anstop.STOP</tt> (0).
	 * The other mode is <tt>COUNTDOWN</tt> (1).
	 */
	private static final int STOP = 0;
	//private static final int COUNTDOWN = 1;

	/**
	 * Counting mode. Two possibilities:
	 *<UL>
	 *<LI> {@link #STOP} (0), counting up from 0
	 *<LI> <tt>Anstop.COUNTDOWN</tt> (1), counting down from a time set by the user
	 *</UL>
	 */
	private int v;

	/** is the clock currently running? */
	public boolean isStarted = false;

	/** has the clock ran since its last reset? This is not an 'isPaused' flag, because
	 *  it's set true when the counting begins and {@link #isStarted} is set true.
	 */
	public boolean wasStarted = false;

	clockThread threadS;
	countDownThread threadC;
	Anstop parent;
	dsechandler dsech;
	sechandler sech;
	minhandler minh;
	hourhandler hourh;
	
	int dsec = 0;
	int sec = 0;
	int min = 0;
	int hour = 0;

	/**
	 * For countdown mode, the initial seconds, minutes, hours,
	 * as set by {@link #reset(int, int, int, int)},
	 * stored as a total number of seconds.
	 * Used by {@link #adjClockOnAppResume(boolean, long)}.
	 */
	private int countdnTotalSeconds = 0;  

	/**
	 * If running, the actual start time, and the adjusted start time after pauses.
	 * (If there are no pauses, they are identical.  Otherwise, the difference
	 * is the amount of paused time.)
	 *<P>
	 * When counting up, the amount of time on the clock
	 * is the current time minus <tt>clockStartTimeAdj</tt>
	 *<P>
	 * Taken from {@link System#currentTimeMillis()}.
	 * @since 1.3
	 */
	private long startTimeActual, startTimeAdj;

	/**
	 * If {@link #wasStarted}, the time when paused by calling {@link #count()},
	 * taken from {@link System#currentTimeMillis()}.  Otherwise -1.
	 */
	private long stopTime;

	/**
	 * Time when {@link android.app.Activity#onPause() Activity.onPause()} was called, or <tt>-1L</tt>.
	 * Used by {@link #onAppPause()}, {@link #onAppResume()}.
	 */
	private long appPauseTime;

	/**
	 * Time when {@link #restoreFromSaveStateBundle(Bundle)} was called, or <tt>-1L</tt>.
	 * Used by {@link #onAppResume()}, to prevent 2 adjustments after a restore.
	 */
	private long appBundleRestoreTime;
	
	public NumberFormat nf;
	
	
	public Clock(Anstop parent) {
		this.parent = parent;
		nf = NumberFormat.getInstance();
		
		nf.setMinimumIntegerDigits(2);  // The minimum Digits required is 2
		nf.setMaximumIntegerDigits(2); // The maximum Digits required is 2


		dsech = new dsechandler();
		sech = new sechandler();
		minh = new minhandler();
		hourh = new hourhandler();

		// these are also set in reset(), along with other state fields
		appPauseTime = -1L;
		appBundleRestoreTime = -1L;
		stopTime = -1L;
		startTimeActual = -1L;
		startTimeAdj = -1L;
	}
	
	/**
	 * Save the clock's current state to a bundle.
	 * For use with {@link Activity#onSaveInstanceState(Bundle)}.
	 *<UL>
	 * <LI> clockActive  1 or 0
	 * <LI> clockWasActive  1 or 0
	 * <LI> clockV    mode (clock.v)
	 * <LI> clockAnstopCurrent  mode (anstop.current)
	 * <LI> clockDigits  if clockActive: array hours, minutes, seconds, dsec
	 * <LI> clockLaps  lap text, if any (CharSequence)
	 * <LI> clockBundleSaveTime current time when bundle saved, from {@link System#currentTimeMillis()}
	 * <LI> clockStartTimeActual  actual time when clock was started, from {@link System#currentTimeMillis()}
	 * <LI> clockStartTimeAdj  <tt>clockStartTimeActual</tt> adjusted forward to remove any
	 *         time spent paused.  When counting up, the amount of time on the clock
	 *         is the current time minus <tt>clockStartTimeAdj</tt>
	 * <LI> clockStopTime  time when clock was last stopped(paused)
	 * <LI> clockCountHour  In Countdown mode, the starting hour spinner; not set if hourSpinner null
	 * <LI> clockCountMin, clockCountSec  Countdown minutes, seconds; same situation as <tt>clockCountHour</tt>
	 *</UL>
	 * @param outState Bundle to save into
	 * @return true if clock was running, false otherwise
	 * @see #restoreFromSaveStateBundle(Bundle)
	 * @since 1.3
	 */
	public boolean fillSaveStateBundle(Bundle outState) {
		final long savedAtTime = System.currentTimeMillis();

		outState.putInt("clockV", v);
		outState.putInt("clockAnstopCurrent", parent.getCurrentMode());
		int[] hmsd = new int[]{ hour, min, sec, dsec };
		outState.putIntArray("clockDigits", hmsd);
		outState.putInt("clockActive", isStarted ? 1 : 0);
		outState.putInt("clockWasActive", wasStarted ? 1 : 0);
		outState.putLong("clockBundleSaveTime", savedAtTime);
		outState.putLong("clockStopTime", stopTime);
		if (parent.lapView != null)
			outState.putCharSequence("clockLaps", parent.lapView.getText());
		outState.putLong("clockStartTimeActual", startTimeActual);
		outState.putLong("clockStartTimeAdj", startTimeAdj);
		if (parent.hourSpinner != null)
		{
			outState.putInt("clockCountHour", parent.hourSpinner.getSelectedItemPosition());
			outState.putInt("clockCountMin", parent.minSpinner.getSelectedItemPosition());
			outState.putInt("clockCountSec", parent.secSpinner.getSelectedItemPosition());
		}

		return isStarted;
	}

	/**
	 * Record the time when {@link android.app.Activity#onPause() Activity.onPause()} was called.
	 * @see #onAppResume()
	 * @since 1.3
	 */
	public void onAppPause()
	{
		appPauseTime = System.currentTimeMillis();
		if((threadS != null) && threadS.isAlive())
		{
			threadS.interrupt();
			threadS = null;
		}
		if((threadC != null) && threadC.isAlive())
		{
			threadC.interrupt();
			threadC = null;
		}
	}

	/**
	 * Adjust the clock when the app is resumed.
	 * Also adjust the clock-display fields.
	 * @see #onAppPause()
	 * @since 1.3
	 */
	public void onAppResume()
	{
		if (! isStarted)
			return;

		if (appPauseTime > appBundleRestoreTime)
			adjClockOnAppResume(false, System.currentTimeMillis());

		if(v == STOP) {
			if((threadS != null) && threadS.isAlive())
				threadS.interrupt();
			threadS = new clockThread();
			threadS.start();
		}
		else {
			if((threadC != null) && threadC.isAlive())
				threadC.interrupt();
			threadC = new countDownThread();
			threadC.start();
		}
	}

	/**
	 * Restore our state (start time millis, etc) and keep going.
	 *<P>
	 * Must call AFTER the GUI elements (parent.dsecondsView, etc) exist.
	 * Thus you must read <tt>clockAnstopCurrent</tt> from the bundle yourself,
	 * and set the GUI mode accordingly, before calling this method.
	 *<P>
	 * Will call count() if clockStarted == 1 in the bundle, unless we've counted down to 0:0:0.
	 * For the bundle contents, see {@link #fillSaveStateBundle(Bundle)}.
	 * @param inState  bundle containing our state
	 * @return true if clock was running when saved, false otherwise
	 * @since 1.3
	 */
	public boolean restoreFromSaveStateBundle(Bundle inState) {
		long restoredAtTime = System.currentTimeMillis();
		appBundleRestoreTime = restoredAtTime;
		if ((inState == null) || ! inState.containsKey("clockActive"))
			return false;

		v = inState.getInt("clockV");

		// read the counting fields
		{
			final int[] hmsd = inState.getIntArray("clockDigits");
			hour = hmsd[0];
			min  = hmsd[1];
			sec  = hmsd[2];
			dsec = hmsd[3];
		}

		final boolean bundleClockActive = (1 == inState.getInt("clockActive"));
		wasStarted = (1 == inState.getInt("clockWasActive"));
		final long savedAtTime = inState.getLong("clockBundleSaveTime", restoredAtTime);
		startTimeActual = inState.getLong("clockStartTimeActual", savedAtTime);
		startTimeAdj = inState.getLong("clockStartTimeAdj", startTimeActual);
		stopTime = inState.getLong("clockStopTime", -1L);
		if (parent.lapView != null)
		{
			CharSequence laptext = inState.getCharSequence("clockLaps");
			if (laptext != null)
				parent.lapView.setText(laptext);
			else
				parent.lapView.setText("");
		}
		if (parent.hourSpinner != null)
		{
			parent.hourSpinner.setSelection(inState.getInt("clockCountHour"));
			parent.minSpinner.setSelection(inState.getInt("clockCountMin"));
			parent.secSpinner.setSelection(inState.getInt("clockCountSec"));
		}

		// Adjust and continue the clock thread:
		// re-read current time for most accuracy
		if (bundleClockActive)
		{
			restoredAtTime = System.currentTimeMillis();
			appBundleRestoreTime = restoredAtTime;
			adjClockOnAppResume(false, restoredAtTime);
		} else {
			adjClockOnAppResume(true, 0L);
		}

		isStarted = false;  // must be false before calling count()
		if (bundleClockActive)
		{
			// Read the values from text elements we've just set, and start it:
			// In countdown mode, will check if we're past 0:0:0 by now.
			count();
		}
		return isStarted;
	}

	/**
	 * Adjust the clock fields ({@link #hour}, {@link #min}, etc)
	 * and the display fields ({@link Anstop#hourView}, etc)
	 * based on the application being paused for a period of time.
	 *<P>
	 * If <tt>adjDisplayOnly</tt> is false, do not call unless {@link #isStarted}.
	 *<P>
	 * Used with {@link #onAppResume()} and {@link #restoreFromSaveStateBundle(Bundle)}.
	 *
	 * @param adjDisplayOnly  If true, update the display fields based on
	 *    the current hour, min, sec, dsec internal field values,
	 *    instead of adjusting those internal values.
	 *    <tt>savedAtTime</tt>, <tt>resumedAtTime</tt> are ignored.
	 * @param resumedAtTime  Time when the app was resumed, from {@link System#currentTimeMillis()}
	 */
	private void adjClockOnAppResume
	    (final boolean adjDisplayOnly, final long resumedAtTime)
	{
		if (! adjDisplayOnly)
		{
			long ttotal;

			// based on our mode, adjust dsec, sec, min, hour:
			switch (v)
			{
			case STOP:
				ttotal = resumedAtTime - startTimeAdj;
				break;
	
			default:  // Anstop.COUNTDOWN
				ttotal = (countdnTotalSeconds * 1000L)
				    - (resumedAtTime - startTimeAdj);
				if (ttotal < 0)
					ttotal = 0;  // don't go past end of countdown
			}

			dsec = ((int) (ttotal % 1000L)) / 100;
			ttotal /= 1000L;
			sec = (int) (ttotal % 60L);
			ttotal /= 60L;
			min = (int) (ttotal % 60L);
			ttotal /= 60L;
			hour = (int) ttotal;
		}

		if (parent.dsecondsView != null)
			parent.dsecondsView.setText(Integer.toString(dsec));
		if (parent.secondsView != null)
			parent.secondsView.setText(Integer.toString(sec));
		if (parent.minView != null)
			parent.minView.setText(Integer.toString(min));
		if (parent.hourView != null)
			parent.hourView.setText(Integer.toString(hour));
	}

	/**
	 * Get the current value of this timer.
	 * @return a stringbuffer of the form "#h mm:ss:d"
	 * @since 1.xx
	 */
	public StringBuffer getCurrentValue()
	{
		StringBuffer sb = new StringBuffer();
		sb.append(hour);
		sb.append("h ");
		sb.append(nf.format(min));
		sb.append(':');
		sb.append(nf.format(sec));
		sb.append(':');
		sb.append(dsec);
		return sb;
	}

	/**
	 * Get the actual start time.
	 * @return Start time, of the form used by {@link System#currentTimeMillis()}.
	 */
	public long getStartTimeActual() { return startTimeActual; }

	/**
	 * Reset the clock while stopped, and maybe change modes.  {@link #isStarted} must be false.
	 * If <tt>newMode</tt> is {@link #STOP}, the clock will be reset to 0,
	 * and <tt>h</tt>, <tt>m</tt>, <tt>s</tt> are ignored.
	 *
	 * @param newMode  new mode to set, or -1 to leave as is
	 * @param h  for countdown mode, hour to reset the clock to
	 * @param m  minute to reset the clock to
	 * @param s  second to reset the clock to
	 * @return true if was reset, false if was not reset because {@link #isStarted} is true.
	 */
	public boolean reset(final int newMode, final int h, final int m, final int s)
	{
		if (isStarted)
			return false;

		if (newMode != -1)
			v = newMode;

		wasStarted = false;
		appPauseTime = -1L;
		appBundleRestoreTime = -1L;
		stopTime = -1L;
		startTimeActual = -1L;
		startTimeAdj = -1L;

		if (v == STOP)
		{
			hour = 0;
			min = 0;
			sec = 0;
		} else {  // COUNTDOWN
			hour = h;
			min = m;
			sec = s;
			countdnTotalSeconds = ((h * 60) + m) * 60 + s;
		}
		dsec = 0;

		return true;
	}

	/**
	 * Start or stop(pause) counting.
	 * For <tt>COUNTDOWN</tt> mode, you must first call {@link #reset(int, int, int, int)}
	 * or set the {@link #hour}, {@link #min}, {@link #sec} fields.
	 */
	public void count() {
		final long now = System.currentTimeMillis();

		if(!isStarted) {

			if (! wasStarted)
			{
				startTimeActual = now;
				startTimeAdj = startTimeActual;
			} else {
				startTimeAdj += (now - stopTime);
			}

			isStarted = true;
			wasStarted = true;
			if(v == STOP) {
				if((threadS != null) && threadS.isAlive())
					threadS.interrupt();
				threadS = new clockThread();
				threadS.start();
			}
				
			else {
				// COUNTDOWN
				if((threadC != null) && threadC.isAlive())
					threadC.interrupt();
				if ((dsec > 0) || (sec > 0) || (min > 0) || (hour > 0))
				{
					threadC = new countDownThread();
					threadC.start();
				} else {
					isStarted = false;
				}
			}
			
			
		}
		else {
			isStarted = false;
			stopTime = now;
			
			if(v == STOP) {
				if(threadS.isAlive())
					threadS.interrupt();
			}
				
			else {
				if(threadC.isAlive())
					threadC.interrupt();
			}
		}
			
	}
	
	private class clockThread extends Thread {		
		public clockThread() { setDaemon(true); }

		@Override
		public void run() {
			
			while(true) {
				dsec++;
				
				if(dsec == 10) {
					sec++;
					dsec = 0;
					sech.sendEmptyMessage(MAX_PRIORITY);
				
					if(sec == 60) {
						min++;
						sec = 0;
						minh.sendEmptyMessage(MAX_PRIORITY);
						
						
						if(min == 60) {
							hour++;
							min = 0;
							hourh.sendEmptyMessage(MAX_PRIORITY);
							minh.sendEmptyMessage(MAX_PRIORITY);
						}
					}
				}
				
				
				dsech.sendEmptyMessage(MAX_PRIORITY);
				
				try {
					sleep(100);
				}
				catch ( InterruptedException e) {
					return;
				}
			}
			
			
		}
		
	}
	
	private class countDownThread extends Thread {
		public countDownThread() { setDaemon(true); }

		@Override
		public void run() {
			
			
			if(hour == 0 && min == 0 && sec == 0 && dsec == 0) {
				isStarted = false;
				parent.modeMenuItem.setEnabled(true);
				parent.saveMenuItem.setEnabled(true);
				return;
			}
			
			while(true) {
				

				
				if(dsec == 0) {
						
					
					if(sec == 0) {
						if(min == 0) {
							if(hour != 0) {
								hour--;
								min = 60;
								hourh.sendEmptyMessage(MAX_PRIORITY);
								minh.sendEmptyMessage(MAX_PRIORITY);
								
							}
						}
						
						if(min != 0) {
							min--;
							sec = 60;
							minh.sendEmptyMessage(MAX_PRIORITY);
						}
						
					}					
					
					if(sec != 0) {
						sec--;
						dsec = 10;
						sech.sendEmptyMessage(MAX_PRIORITY);
					}
										
				}
				dsec--;
				
				
				
				dsech.sendEmptyMessage(MAX_PRIORITY);
				
				try {
					sleep(100);
				}
				catch ( InterruptedException e) {
					return;
				}
				
				
				if(hour == 0 && min == 0 && sec == 0 && dsec == 0) {
					isStarted = false;
					parent.modeMenuItem.setEnabled(true);
					parent.saveMenuItem.setEnabled(true);
					return;
				}
					
			}
			
			
		}
		
	}
	
	private class dsechandler extends Handler {
		@Override
		public void handleMessage (Message msg) {
			parent.dsecondsView.setText("" + dsec);
		}
	}
	
	private class sechandler extends Handler {
		@Override
		public void handleMessage (Message msg) {
			parent.secondsView.setText("" + nf.format(sec));
		}
	}
	
	private class minhandler extends Handler {
		@Override
		public void handleMessage (Message msg) {
			parent.minView.setText("" + nf.format(min));
		}
	}
	
	private class hourhandler extends Handler {
		@Override
		public void handleMessage (Message msg) {
			parent.hourView.setText("" + hour);
		}
	}
	
		
	
}
