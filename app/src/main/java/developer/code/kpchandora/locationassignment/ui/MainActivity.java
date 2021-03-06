package developer.code.kpchandora.locationassignment.ui;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job;
import com.firebase.jobdispatcher.Lifetime;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStates;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;

import developer.code.kpchandora.locationassignment.MyApplication;
import developer.code.kpchandora.locationassignment.R;
import developer.code.kpchandora.locationassignment.RootAnimActivity;
import developer.code.kpchandora.locationassignment.adapter.LocationAdapter;
import developer.code.kpchandora.locationassignment.roomdb.database.LocationDatabase;
import developer.code.kpchandora.locationassignment.roomdb.entities.LocationEntity;
import developer.code.kpchandora.locationassignment.roomdb.entities.LocationHistory;
import developer.code.kpchandora.locationassignment.service.LocationService;
import developer.code.kpchandora.locationassignment.service.MyJobService;
import developer.code.kpchandora.locationassignment.viewmodel.LocationViewModel;

public class MainActivity extends RootAnimActivity {

    private static final String TAG = "MainActivity";

    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int REQUEST_CHECK_SETTINGS = 12;
    public final static String CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE";
    private FirebaseJobDispatcher jobDispatcher;

    private LocationViewModel viewModel;
    private RecyclerView locationRecyclerView;
    private Button startButton;
    private ImageView emptyView;
    private TextView fetchingTextView;

    private GoogleApiClient mGoogleApiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        locationRecyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyImageView);
        fetchingTextView = findViewById(R.id.emptyTextView);
        locationRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        viewModel = ViewModelProviders.of(this).get(LocationViewModel.class);

        jobDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(this));
        startButton = findViewById(R.id.locationButton);
        startButtonClick();
        if (!checkPermission()) {
            askPermission();
        }

        schedule();
        final LocationAdapter adapter = new LocationAdapter(this);
        locationRecyclerView.setAdapter(adapter);

        viewModel.getListLiveData().observe(this, new Observer<List<LocationEntity>>() {
            @Override
            public void onChanged(@Nullable List<LocationEntity> locationEntities) {
                if (locationEntities != null && locationEntities.size() > 0) {
                    emptyView.setVisibility(View.GONE);
                    fetchingTextView.setVisibility(View.GONE);
                } else {
                    emptyView.setVisibility(View.VISIBLE);
                }
                adapter.setLocation(locationEntities);
            }
        });

        createNotificationChannel();
        restoreData();

    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Location";
            String description = "Fetches location";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel("noti_id", name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager == null) {
                return;
            }
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.history_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.history) {
            startActivity(new Intent(MainActivity.this, HistoryActivity.class));
        }
        if (item.getItemId() == R.id.sign_out) {
            signOutUser();
        }
        return super.onOptionsItemSelected(item);
    }

    private void signOutUser() {
        FirebaseAuth.getInstance().signOut();
        startActivity(new Intent(MainActivity.this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        stopService(new Intent(MainActivity.this, LocationService.class));
        finish();
    }

    private boolean isMyServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean checkPermission() {

        if (ContextCompat.checkSelfPermission(MyApplication.getAppContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(MyApplication.getAppContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    private void askPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }


    private void schedule() {
        Job job = jobDispatcher.newJobBuilder()
                .setService(MyJobService.class)
                .setTag("unique_tag")
                .setLifetime(Lifetime.UNTIL_NEXT_BOOT)
                .build();

        jobDispatcher.mustSchedule(job);
    }

    private void startButtonClick() {

        if (isMyServiceRunning(LocationService.class)) {
            startButton.setText("Stop");
        } else {
            List<LocationEntity> locationEntities = LocationDatabase.getInstance(getApplication()).locationDao().getAllEntities();
            if (locationEntities.size() > 0) {
                Log.i(TAG, "startButtonClick: ");
                AsyncTask.execute(new LocationService().new InsertDataRunnable());
            }
        }

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (manager != null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    startFetchingLocation();
                } else {
                    startButton.setEnabled(false);
                    showLocationDialog();
                }
            }
        });
    }

    private void startFetchingLocation() {
        if (startButton.getText().toString().equalsIgnoreCase("Start")) {
            startButton.setText("Stop");
            fetchingTextView.setVisibility(View.VISIBLE);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.startService(new Intent(MainActivity.this, LocationService.class));
                }
            }, 2000);

        } else {
            startButton.setText("Start");
            stopService(new Intent(MainActivity.this, LocationService.class));
            startActivity(new Intent(MainActivity.this, HistoryActivity.class));
            fetchingTextView.setVisibility(View.GONE);
        }
    }


    public void settingsrequest() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(30 * 1000);
        locationRequest.setFastestInterval(5 * 1000);
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest);
        builder.setAlwaysShow(true); //this is the key ingredient

        PendingResult<LocationSettingsResult> result =
                LocationServices.SettingsApi.checkLocationSettings(mGoogleApiClient, builder.build());
        result.setResultCallback(new ResultCallback<LocationSettingsResult>() {
            @Override
            public void onResult(LocationSettingsResult result) {
                final Status status = result.getStatus();
                final LocationSettingsStates state = result.getLocationSettingsStates();
                switch (status.getStatusCode()) {
                    case LocationSettingsStatusCodes.SUCCESS:
                        startFetchingLocation();
                        break;
                    case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                        // Location settings are not satisfied. But could be fixed by showing the user
                        // a dialog.
                        try {
                            // Show the dialog by calling startResolutionForResult(),
                            // and check the result in onActivityResult().
                            status.startResolutionForResult(MainActivity.this, REQUEST_CHECK_SETTINGS);
                        } catch (IntentSender.SendIntentException e) {
                            // Ignore the error.
                        }
                        break;
                    case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                        // Location settings are not satisfied. However, we have no way to fix the
                        // settings so we won't show the dialog.
                        break;
                }
            }
        });
    }

    private void showLocationDialog() {
        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(MainActivity.this)
                    .addApi(LocationServices.API)
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(@Nullable Bundle bundle) {
                            // TODO: 25/8/18
                        }

                        @Override
                        public void onConnectionSuspended(int i) {
                            mGoogleApiClient.connect();
                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                            // TODO: 25/8/18
                        }
                    }).build();
            mGoogleApiClient.connect();
        }

        settingsrequest();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {

            case REQUEST_CHECK_SETTINGS:
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        startButton.setEnabled(true);
                        startFetchingLocation();
                        break;
                    case Activity.RESULT_CANCELED:
                        startButton.setEnabled(true);
                        Toast.makeText(MainActivity.this, "Please turn on GPS", Toast.LENGTH_SHORT).show();//keep asking if imp or do whatever
                        break;
                }
                break;
        }
    }

    private void restoreData() {

        final int[] size = new int[1];
        size[0] = 0;

        List<LocationHistory> list = LocationDatabase.getInstance(getApplication()).historyDao().getAllHistoryData();
        if (list != null && list.size() > 0) {
            size[0] = list.size();
        }
        Log.i(TAG, "restoreData: " + size[0]);
        DatabaseReference reference =
                FirebaseDatabase.getInstance().getReference("visited").child(FirebaseAuth.getInstance().getUid());
        reference.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.getValue() != null && size[0] == 0) {
                    Log.i(TAG, "onDataChange: " + size[0]);
                    new RestoreDataAsyncTask().execute(dataSnapshot);
                    size[0] = 1;
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.i(TAG, "onCancelled: " + databaseError.getDetails());
            }
        });
    }

    private class RestoreDataAsyncTask extends AsyncTask<DataSnapshot, Void, Void> {

        @Override
        protected Void doInBackground(DataSnapshot... dataSnapshots) {
            Iterable<DataSnapshot> dataSnapshot = dataSnapshots[0].getChildren();
            for (DataSnapshot snapshot : dataSnapshot) {
                Log.i(TAG, "doInBackground: " + snapshot.getKey());
                LocationHistory history = new LocationHistory();
                history.setTimeStamp(snapshot.getKey());
                history.setLocationEntityList((List<LocationEntity>) snapshot.getValue());
                LocationDatabase.getInstance(getApplication()).historyDao().insertLocationHistory(history);
            }
            return null;
        }
    }

}
