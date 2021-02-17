package wangdaye.com.geometricweather.utils.managers;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import wangdaye.com.geometricweather.basic.models.Location;
import wangdaye.com.geometricweather.basic.models.weather.Weather;

/**
 * Time manager.
 * */

public class TimeManager {

    private static volatile TimeManager sInstance;
    public static synchronized TimeManager getInstance(Context context) {
        synchronized (TimeManager.class) {
            if (sInstance == null) {
                sInstance = new TimeManager(context);
            }
        }
        return sInstance;
    }

    private boolean mDayTime;

    private static final String PREFERENCE_NAME = "time_preference";
    private static final String KEY_DAY_TIME = "day_time";

    private TimeManager(Context context) {
        mDayTime = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DAY_TIME, true);
    }

    public boolean isDayTime() {
        return mDayTime;
    }

    public TimeManager update(Context context, @Nullable Location location) {
        if (location == null) {
            return this;
        }

        mDayTime = isDaylight(location);

        SharedPreferences.Editor editor = context.getSharedPreferences(
                PREFERENCE_NAME, Context.MODE_PRIVATE
        ).edit();
        editor.putBoolean(KEY_DAY_TIME, mDayTime);
        editor.apply();
        return this;
    }

    public static boolean isDaylight(@NonNull Location location) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(location.getTimeZone());
        int time = 60 * calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE);

        Weather weather = location.getWeather();
        if (weather != null ) {
            Date riseDate = weather.getDailyForecast().get(0).sun().getRiseDate();
            Date setDate = weather.getDailyForecast().get(0).sun().getSetDate();
            if (riseDate != null && setDate != null) {
                calendar.setTimeZone(TimeZone.getDefault());

                calendar.setTime(riseDate);
                int sunrise = 60 * calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE);

                calendar.setTime(setDate);
                int sunset = 60 * calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE);

                return sunrise < time && time < sunset;
            }
        }

        int sr = 60 * 6;
        int ss = 60 * 18;
        return sr < time && time < ss;
    }

    public static boolean is12Hour(Context context) {
        return !DateFormat.is24HourFormat(context);
    }
}
