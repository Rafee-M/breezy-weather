package wangdaye.com.geometricweather.main;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import wangdaye.com.geometricweather.R;
import wangdaye.com.geometricweather.basic.GeoActivity;
import wangdaye.com.geometricweather.basic.model.location.Location;
import wangdaye.com.geometricweather.db.DatabaseHelper;
import wangdaye.com.geometricweather.main.model.Indicator;
import wangdaye.com.geometricweather.main.model.LocationResource;
import wangdaye.com.geometricweather.settings.SettingsOptionManager;
import wangdaye.com.geometricweather.utils.SnackbarUtils;

public class MainActivityViewModel extends ViewModel
        implements MainActivityRepository.OnLocationCompletedListener {

    private MutableLiveData<LocationResource> currentLocation;
    private MutableLiveData<Indicator> indicator;

    private List<Location> locationList;
    private int currentPositionIndex;
    private int currentIndex;

    private MainActivityRepository repository;

    private static final int INVALID_LOCATION_INDEX = -1;

    public MainActivityViewModel() {
        currentLocation = new MutableLiveData<>();

        indicator = new MutableLiveData<>();
        indicator.setValue(new Indicator(1, 0));

        locationList = new ArrayList<>();
        currentPositionIndex = INVALID_LOCATION_INDEX;
        currentIndex = 0;
    }

    public void reset(GeoActivity activity) {
        LocationResource resource = currentLocation.getValue();
        if (resource != null) {
            init(activity, resource.data);
        }
    }

    public void init(GeoActivity activity, @NonNull Location location) {
        init(activity, location.getFormattedId());
    }

    public void init(GeoActivity activity, @Nullable String formattedId) {
        if (repository == null) {
            repository = new MainActivityRepository(activity);
        }

        DatabaseHelper databaseHelper = DatabaseHelper.getInstance(activity);
        locationList = databaseHelper.readLocationList();
        for (int i = 0; i < locationList.size(); i ++) {
            locationList.get(i).setWeather(databaseHelper.readWeather(locationList.get(i)));
            if (locationList.get(i).isCurrentPosition()) {
                currentPositionIndex = i;
            }
            if (locationList.get(i).equals(formattedId)) {
                currentIndex = i;
            }
        }

        setLocation(activity, true, false);
    }

    public void setLocation(GeoActivity activity, int offset) {
        currentIndex = getLocationIndexFromList(activity, offset);
        setLocation(activity, offset != 0, false);
    }

    private void setLocation(GeoActivity activity, boolean resetIndicator, boolean updatedInBackground) {
        Location current = locationList.get(currentIndex);

        if (resetIndicator) {
            resetIndicator(activity);
        }

        float pollingIntervalInHour = SettingsOptionManager.getInstance(activity)
                .getUpdateInterval()
                .getIntervalInHour();
        if (current.isUsable()
                && current.getWeather() != null
                && current.getWeather().isValid(pollingIntervalInHour)) {
            repository.cancel();
            currentLocation.setValue(LocationResource.success(current, updatedInBackground));
        } else {
            currentLocation.setValue(LocationResource.loading(current));
            updateWeather(activity);
        }
    }

    private void resetIndicator(Context context) {
        int index = 0;
        int total = 0;
        for (int i = 0; i < locationList.size(); i ++) {
            if (i == currentIndex) {
                index = total;
            }
            if (currentPositionIndex == INVALID_LOCATION_INDEX
                    || !locationList.get(i).isResidentPosition()
                    || !locationList.get(i).isCloseTo(context, locationList.get(currentPositionIndex))) {
                total ++;
            }
        }
        indicator.setValue(new Indicator(total, index));
    }

    public void updateLocationFromBackground(GeoActivity activity, @Nullable String formattedId) {
        int index = indexLocation(locationList, formattedId);
        if (index == INVALID_LOCATION_INDEX) {
            return;
        }

        Location location = DatabaseHelper.getInstance(activity).readLocation(locationList.get(index));
        if (location == null) {
            return;
        }

        location.setWeather(DatabaseHelper.getInstance(activity).readWeather(location));
        locationList.set(index, location);
        if (index == currentIndex) {
            if (activity.isForeground()) {
                SnackbarUtils.showSnackbar(activity, activity.getString(R.string.feedback_updated_in_background));
            }
            setLocation(activity, location.isCurrentPosition(), true);
        }
    }

    private int indexLocation(List<Location> locationList, @Nullable String formattedId) {
        if (TextUtils.isEmpty(formattedId)) {
            return INVALID_LOCATION_INDEX;
        }

        for (int i = 0; i < locationList.size(); i ++) {
            if (locationList.get(i).equals(formattedId)) {
                return i;
            }
        }

        return INVALID_LOCATION_INDEX;
    }

    public int getLocationIndexFromList(GeoActivity activity, int offset) {
        if (offset == 0) {
            return currentIndex;
        }

        int index = currentIndex;
        index += locationList.size() + offset;
        index %= locationList.size();
        while (currentPositionIndex != INVALID_LOCATION_INDEX
                && locationList.get(index).isResidentPosition()
                && locationList.get(index).isCloseTo(activity, locationList.get(currentPositionIndex))) {
            index += locationList.size() + (offset > 0 ? 1 : -1);
            index %= locationList.size();
        }
        return index;
    }

    public Location getLocationFromList(GeoActivity activity, int offset) {
        return locationList.get(getLocationIndexFromList(activity, offset));
    }

    public void updateWeather(GeoActivity activity) {
        repository.cancel();

        assert currentLocation.getValue() != null;
        Location location = currentLocation.getValue().data;

        currentLocation.setValue(LocationResource.loading(location));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && location.isCurrentPosition()) {
            // check permissions.
            List<String> permissionList = repository.getLocatePermissionList();
            for (int i = permissionList.size() - 1; i >= 0; i --) {
                if (ActivityCompat.checkSelfPermission(activity, permissionList.get(i))
                        == PackageManager.PERMISSION_GRANTED) {
                    permissionList.remove(i);
                }
            }

            if (permissionList.size() != 0) {
                // request permission.
                activity.requestPermissions(permissionList.toArray(new String[0]), 0,
                        (requestCode, permission, grantResult) -> {
                            for (int i = 0; i < permission.length && i < grantResult.length; i++) {
                                if (isPivotalPermission(permission[i])
                                        && grantResult[i] != PackageManager.PERMISSION_GRANTED) {
                                    if (location.isUsable()) {
                                        repository.getWeather(activity,
                                                currentLocation, locationList, false, this);
                                    } else {
                                        currentLocation.setValue(
                                                LocationResource.error(location, true));
                                    }
                                    return;
                                }
                            }
                            repository.getWeather(activity, currentLocation, locationList, true, this);
                        });
                return;
            }
        }

        repository.getWeather(activity, currentLocation, locationList, location.isCurrentPosition(), this);
    }

    private boolean isPivotalPermission(String permission) {
        return permission.equals(Manifest.permission.ACCESS_COARSE_LOCATION)
                || permission.equals(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public MutableLiveData<LocationResource> getCurrentLocation() {
        return currentLocation;
    }

    public MutableLiveData<Indicator> getIndicator() {
        return indicator;
    }

    @Nullable
    public Location getCurrentLocationValue() {
        LocationResource resource = currentLocation.getValue();
        if (resource != null) {
            return resource.data;
        } else {
            return null;
        }
    }

    public int getIndicatorCountValue() {
        if (indicator.getValue() != null) {
            return indicator.getValue().total;
        } else {
            return 1;
        }
    }

    public int getIndicatorIndexValue() {
        if (indicator.getValue() != null) {
            return indicator.getValue().index;
        } else {
            return 0;
        }
    }

    public List<Location> getLocationList() {
        return locationList;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (repository != null) {
            repository.cancel();
        }
    }

    // on location completed listener.

    @Override
    public void onCompleted(Context context) {
        resetIndicator(context);
    }
}