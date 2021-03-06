package com.mopub.mobileads;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.provider.CalendarContract;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.webkit.URLUtil;
import android.widget.*;
import com.mopub.mobileads.MraidView.ExpansionStyle;
import com.mopub.mobileads.MraidView.NativeCloseButtonStyle;
import com.mopub.mobileads.MraidView.PlacementType;
import com.mopub.mobileads.MraidView.ViewState;
import com.mopub.mobileads.factories.HttpClientFactory;
import com.mopub.mobileads.util.HttpResponses;
import com.mopub.mobileads.util.Streams;
import com.mopub.mobileads.util.VersionCode;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.InvalidParameterException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.mopub.mobileads.MraidCommandRegistry.*;
import static com.mopub.mobileads.MraidCommandStorePicture.MIME_TYPE_HEADER;
import static com.mopub.mobileads.MraidView.BaseMraidListener;
import static com.mopub.mobileads.resource.Drawables.INTERSTITIAL_CLOSE_BUTTON_NORMAL;
import static com.mopub.mobileads.resource.Drawables.INTERSTITIAL_CLOSE_BUTTON_PRESSED;

class MraidDisplayController extends MraidAbstractController {
    private static final String LOGTAG = "MraidDisplayController";
    private static final long VIEWABILITY_TIMER_MILLIS = 3000;
    private static final int CLOSE_BUTTON_SIZE_DP = 50;
    private static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZZZZZ";
    private static final int MAX_NUMBER_DAYS_IN_MONTH = 31;

    // The view's current state.
    private ViewState mViewState = ViewState.HIDDEN;

    // Tracks whether this controller's view responds to expand() calls.
    private final ExpansionStyle mExpansionStyle;

    // Tracks how this controller's view should display its native close button.
    private final NativeCloseButtonStyle mNativeCloseButtonStyle;

    // Separate instance of MraidView, for displaying "two-part" creatives via the expand(URL) API.
    private MraidView mTwoPartExpansionView;

    // A reference to the root view.
    private FrameLayout mRootView;

    // Tracks whether this controller's view is currently on-screen.
    private boolean mIsViewable;

    // Task that periodically checks whether this controller's view is on-screen.
    private Runnable mCheckViewabilityTask = new Runnable() {
        public void run() {
            boolean currentViewable = checkViewable();
            if (mIsViewable != currentViewable) {
                mIsViewable = currentViewable;
                getMraidView().fireChangeEventForProperty(
                        MraidViewableProperty.createWithViewable(mIsViewable));
            }
            mHandler.postDelayed(this, VIEWABILITY_TIMER_MILLIS);
        }
    };

    // Handler for scheduling viewability checks.
    private Handler mHandler = new Handler();

    // Stores the requested orientation for the Activity to which this controller's view belongs.
    // This is needed to restore the Activity's requested orientation in the event that the view
    // itself requires an orientation lock.
    private final int mOriginalRequestedOrientation;

    private OrientationBroadcastReceiver mOrientationBroadcastReceiver = new OrientationBroadcastReceiver();

    // Native close button, used for expanded content.
    private ImageView mCloseButton;

    // Tracks whether expanded content provides its own, non-native close button.
    private boolean mAdWantsCustomCloseButton;

    // The scale factor for a dip (relative to a 160 dpi screen).
    protected float mDensity;

    // The width of the screen in pixels.
    protected int mScreenWidth = -1;

    // The height of the screen in pixels.
    protected int mScreenHeight = -1;

    // The view's position within its parent.
    private int mViewIndexInParent;

    // A view that replaces the MraidView within its parent view when the MraidView is expanded
    // (i.e. moved to the top of the view hierarchy).
    private FrameLayout mPlaceholderView;
    private FrameLayout mAdContainerLayout;
    private RelativeLayout mExpansionLayout;

    MraidDisplayController(MraidView view, MraidView.ExpansionStyle expStyle,
            MraidView.NativeCloseButtonStyle buttonStyle) {
        super(view);
        mExpansionStyle = expStyle;
        mNativeCloseButtonStyle = buttonStyle;

        Context context = getContext();
        mOriginalRequestedOrientation = (context instanceof Activity) ?
                ((Activity) context).getRequestedOrientation() :
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

        mAdContainerLayout = createAdContainerLayout();
        mExpansionLayout = createExpansionLayout();
        mPlaceholderView = createPlaceholderView();

        initialize();
    }

    private void initialize() {
        mViewState = ViewState.LOADING;
        initializeScreenMetrics();
        initializeViewabilityTimer();
        mOrientationBroadcastReceiver.register(getContext());
    }

    private void initializeScreenMetrics() {
        Context context = getContext();
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);
        mDensity = metrics.density;

        int statusBarHeight = 0, titleBarHeight = 0;
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            Window window = activity.getWindow();
            Rect rect = new Rect();
            window.getDecorView().getWindowVisibleDisplayFrame(rect);
            statusBarHeight = rect.top;
            int contentViewTop = window.findViewById(Window.ID_ANDROID_CONTENT).getTop();
            titleBarHeight = contentViewTop - statusBarHeight;
        }

        int widthPixels = metrics.widthPixels;
        int heightPixels = metrics.heightPixels - statusBarHeight - titleBarHeight;
        mScreenWidth = (int) (widthPixels * (160.0 / metrics.densityDpi));
        mScreenHeight = (int) (heightPixels * (160.0 / metrics.densityDpi));
    }

    private void initializeViewabilityTimer() {
        mHandler.removeCallbacks(mCheckViewabilityTask);
        mHandler.post(mCheckViewabilityTask);
    }

    private int getDisplayRotation() {
        WindowManager wm = (WindowManager) getContext()
                .getSystemService(Context.WINDOW_SERVICE);
        return wm.getDefaultDisplay().getOrientation();
    }

    private void onOrientationChanged(int currentRotation) {
        initializeScreenMetrics();
        getMraidView().fireChangeEventForProperty(
                MraidScreenSizeProperty.createWithSize(mScreenWidth, mScreenHeight));
    }

    public void destroy() {
        mHandler.removeCallbacks(mCheckViewabilityTask);
        try {
            mOrientationBroadcastReceiver.unregister();
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("Receiver not registered")) {
                throw e;
            } // Else ignore this exception.
        }
    }

    protected void initializeJavaScriptState() {
        ArrayList<MraidProperty> properties = new ArrayList<MraidProperty>();
        properties.add(MraidScreenSizeProperty.createWithSize(mScreenWidth, mScreenHeight));
        properties.add(MraidViewableProperty.createWithViewable(mIsViewable));
        getMraidView().fireChangeEventForProperties(properties);

        mViewState = ViewState.DEFAULT;
        getMraidView().fireChangeEventForProperty(MraidStateProperty.createWithViewState(mViewState));
        initializeSupportedFunctionsProperty();
    }

    protected boolean isExpanded() {
        return (mViewState == ViewState.EXPANDED);
    }

    protected void close() {
        if (mViewState == ViewState.EXPANDED) {
            resetViewToDefaultState();
            setOrientationLockEnabled(false);
            mViewState = ViewState.DEFAULT;
            getMraidView().fireChangeEventForProperty(MraidStateProperty.createWithViewState(mViewState));
        } else if (mViewState == ViewState.DEFAULT) {
            getMraidView().setVisibility(View.INVISIBLE);
            mViewState = ViewState.HIDDEN;
            getMraidView().fireChangeEventForProperty(MraidStateProperty.createWithViewState(mViewState));
        }

        if (getMraidView().getMraidListener() != null) {
            getMraidView().getMraidListener().onClose(getMraidView(), mViewState);
        }
    }

    private void resetViewToDefaultState() {
        setNativeCloseButtonEnabled(false);
        mAdContainerLayout.removeAllViewsInLayout();
        mExpansionLayout.removeAllViewsInLayout();
        mRootView.removeView(mExpansionLayout);

        getMraidView().requestLayout();

        ViewGroup parent = (ViewGroup) mPlaceholderView.getParent();
        parent.addView(getMraidView(), mViewIndexInParent);
        parent.removeView(mPlaceholderView);
        parent.invalidate();
    }

    protected void expand(String url, int width, int height, boolean shouldUseCustomClose,
            boolean shouldLockOrientation) {
        if (mExpansionStyle == MraidView.ExpansionStyle.DISABLED) return;

        if (url != null && !URLUtil.isValidUrl(url)) {
            getMraidView().fireErrorEvent(MraidCommandRegistry.MRAID_JAVASCRIPT_COMMAND_EXPAND, "URL passed to expand() was invalid.");
            return;
        }

        // Obtain the root content view, since that's where we're going to insert the expanded 
        // content. We must do this before swapping the MraidView with its place-holder;
        // otherwise, getRootView() will return the wrong view (or null).
        mRootView = (FrameLayout) getMraidView().getRootView().findViewById(android.R.id.content);

        useCustomClose(shouldUseCustomClose);
        setOrientationLockEnabled(shouldLockOrientation);
        swapViewWithPlaceholderView();

        View expansionContentView = getMraidView();
        if (url != null) {
            mTwoPartExpansionView = new MraidView(getContext(), ExpansionStyle.DISABLED,
                    NativeCloseButtonStyle.AD_CONTROLLED, PlacementType.INLINE);
            mTwoPartExpansionView.setMraidListener(new BaseMraidListener() {
                public void onClose(MraidView view, ViewState newViewState) {
                    close();
                }
            });
            mTwoPartExpansionView.loadUrl(url);
            expansionContentView = mTwoPartExpansionView;
        }

        expandLayouts(expansionContentView, (int) (width * mDensity), (int) (height * mDensity));
        mRootView.addView(mExpansionLayout, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.FILL_PARENT, RelativeLayout.LayoutParams.FILL_PARENT));

        if (mNativeCloseButtonStyle == MraidView.NativeCloseButtonStyle.ALWAYS_VISIBLE ||
                (!mAdWantsCustomCloseButton &&
                mNativeCloseButtonStyle != MraidView.NativeCloseButtonStyle.ALWAYS_HIDDEN)) {
            setNativeCloseButtonEnabled(true);
        }

        mViewState = ViewState.EXPANDED;
        getMraidView().fireChangeEventForProperty(MraidStateProperty.createWithViewState(mViewState));
        if (getMraidView().getMraidListener() != null) getMraidView().getMraidListener().onExpand(getMraidView());
    }

    protected void showUserDownloadImageAlert(String imageUrl) {
        if (getContext() instanceof Activity) {
            showUserDialog(imageUrl);
        } else {
            showUserToast();
            downloadImage(imageUrl);
        }
    }

    private void showUserToast() {
        Toast.makeText(getContext(), "Downloading image to Picture gallery", Toast.LENGTH_SHORT).show();
    }

    private void downloadImage(final String uriString) {
        final File pictureStoragePath = getPictureStoragePath();

        pictureStoragePath.mkdirs();

        new Thread(new Runnable() {
            private URI uri;
            private InputStream pictureInputStream;
            private OutputStream pictureOutputStream;
            private MediaScannerConnection mediaScannerConnection;

            @Override
            public void run() {
                uri = URI.create(uriString);

                try {
                    HttpClient httpClient = HttpClientFactory.create();
                    HttpGet httpGet = new HttpGet(uri);

                    HttpResponse httpResponse = httpClient.execute(httpGet);
                    pictureInputStream = httpResponse.getEntity().getContent();

                    String redirectLocation = HttpResponses.extractHeader(httpResponse, "Location");
                    if (redirectLocation != null) {
                        uri = URI.create(redirectLocation);
                    }

                    final String pictureFileName = getFileNameForUriAndHttpResponse(uri, httpResponse);
                    File pictureFile = new File(pictureStoragePath, pictureFileName);
                    final String pictureFileFullPath = pictureFile.toString();
                    pictureOutputStream = new FileOutputStream(pictureFile);

                    Streams.copyContent(pictureInputStream, pictureOutputStream);

                    loadPictureIntoGalleryApp(pictureFileFullPath);
                } catch (Exception exception) {
                    getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_STORE_PICTURE, "Error downloading and saving image file.");
                    Log.d("MoPub", "Error downloading and saving image file.");
                }
            }

            private void loadPictureIntoGalleryApp(final String filename) {
                MoPubMediaScannerConnectionClient mediaScannerConnectionClient = new MoPubMediaScannerConnectionClient(filename, null);
                mediaScannerConnection = new MediaScannerConnection(getContext().getApplicationContext(), mediaScannerConnectionClient);
                mediaScannerConnectionClient.setMediaScannerConnection(mediaScannerConnection);
                mediaScannerConnection.connect();
            }
        }).start();
    }

    private void showUserDialog(final String imageUrl) {
        AlertDialog.Builder alertDialogDownloadImage = new AlertDialog.Builder(getContext());
        alertDialogDownloadImage
                .setTitle("Save Image")
                .setMessage("Download image to Picture gallery?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Okay", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        downloadImage(imageUrl);
                    }
                })
                .setCancelable(true)
                .show();
    }

    protected void showVideo(String videoUrl) {
        MraidVideoPlayerActivity.start(getContext(), getMraidView(), videoUrl);
    }

    protected void getCurrentPosition(){
        getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_GET_CURRENT_POSITION, "Unsupported action getCurrentPosition");
    }

    protected void getDefaultPosition(){
        getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_GET_DEFAULT_POSITION, "Unsupported action getDefaultPosition");
    }

    protected void getMaxSize(){
        getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_GET_MAX_SIZE, "Unsupported action getMaxSize");
    }

    protected void getScreenSize(){
        getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_GET_SCREEN_SIZE, "Unsupported action getScreenSize");
    }

    protected void createCalendarEvent(Map<String, String> params) {
        if(VersionCode.currentApiLevel().isAtLeast(VersionCode.ICE_CREAM_SANDWICH)) {
            try {
                Map<String, Object> calendarParams = translateJSParamsToAndroidCalendarEventMapping(params);
                Intent intent = new Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.item/event");
                for (String key : calendarParams.keySet()) {
                    Object value = calendarParams.get(key);
                    if (value instanceof Long) {
                        intent.putExtra(key, ((Long)value).longValue());
                    } else if (value instanceof Integer) {
                        intent.putExtra(key, ((Integer)value).intValue());
                    } else {
                        intent.putExtra(key, (String)value);
                    }
                }
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getMraidView().getContext().startActivity(intent);
            } catch (ActivityNotFoundException anfe) {
                Log.d(LOGTAG, "no calendar app installed");
                getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_CREATE_CALENDAR_EVENT, "Action is unsupported on this device - no calendar app installed");
            } catch (IllegalArgumentException iae) {
                Log.d(LOGTAG, "create calendar: invalid parameters " + iae.getMessage());
                getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_CREATE_CALENDAR_EVENT, iae.getMessage());
            } catch (Exception exception){
                Log.d(LOGTAG, "could not create calendar event");
                getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_CREATE_CALENDAR_EVENT, "could not create calendar event");
            }
        } else {
            Log.d(LOGTAG, "unsupported action createCalendarEvent for devices pre-ICS");
            getMraidView().fireErrorEvent(MRAID_JAVASCRIPT_COMMAND_CREATE_CALENDAR_EVENT, "Action is unsupported on this device (need Android version Ice Cream Sandwich or above)");
        }
    }

    private Map<String, Object> translateJSParamsToAndroidCalendarEventMapping(Map<String, String> params) throws Exception {
        SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT);
        Map<String, Object> validatedParamsMapping = new HashMap<String, Object>();
        if (!params.containsKey("description") || !params.containsKey("start")) {
            throw new InvalidParameterException("missing start and description fields");
        }

        validatedParamsMapping.put(CalendarContract.Events.TITLE, params.get("description"));
        try {
            Date date = dateFormat.parse(params.get("start"));
            validatedParamsMapping.put(CalendarContract.EXTRA_EVENT_BEGIN_TIME, date.getTime());
            if(params.containsKey("end")) {
                date = dateFormat.parse(params.get("end"));
                validatedParamsMapping.put(CalendarContract.EXTRA_EVENT_END_TIME, date.getTime());
            }
        } catch (ParseException pe) {
            throw new InvalidParameterException("Invalid date format. Date format expecting (yyyy-MM-DDTHH:MM:SS-xx:xx) i.e. 2013-08-14T09:00:00-08:00");
        } catch (NullPointerException npe) {
            throw new InvalidParameterException("invalid calendar event: start or end is null");
        }

        if (params.containsKey("location")) {
            validatedParamsMapping.put(CalendarContract.Events.EVENT_LOCATION, params.get("location"));
        }

        if (params.containsKey("summary")) {
            validatedParamsMapping.put(CalendarContract.Events.DESCRIPTION, params.get("summary"));
        }

        if (params.containsKey("transparency")) {
            validatedParamsMapping.put(
                    CalendarContract.Events.AVAILABILITY,
                    params.get("transparency").equals("transparent") ?
                            CalendarContract.Events.AVAILABILITY_FREE :
                            CalendarContract.Events.AVAILABILITY_BUSY
            );
        }

        validatedParamsMapping.put(CalendarContract.Events.RRULE, parseRecurrenceRule(params));

        return validatedParamsMapping;
    }

    private String parseRecurrenceRule(Map<String, String> params) throws IllegalArgumentException {
        StringBuilder rule = new StringBuilder();
        if (params.containsKey("frequency")) {
            String frequency = params.get("frequency");
            int interval = -1;
            if (params.containsKey("interval")) {
                interval = Integer.parseInt(params.get("interval"));
            }
            if ("daily".equals(frequency)) {
                rule.append("FREQ=DAILY;");
                if (interval != -1) {
                    rule.append("INTERVAL=" + interval + ";");
                }
            } else if("weekly".equals(frequency)) {
                rule.append("FREQ=WEEKLY;");
                if (interval != -1) {
                    rule.append("INTERVAL=" + interval + ";");
                }
                if (params.containsKey("daysInWeek")) {
                    String weekdays = translateWeekIntegersToDays(params.get("daysInWeek"));
                    if (weekdays == null) {
                       throw new IllegalArgumentException("invalid ");
                    }
                    rule.append("BYDAY=" + weekdays + ";");
                }
            } else if("monthly".equals(frequency)) {
                rule.append("FREQ=MONTHLY;");
                if (interval != -1) {
                    rule.append("INTERVAL=" + interval + ";");
                }
                if (params.containsKey("daysInMonth")) {
                    String monthDays = translateMonthIntegersToDays(params.get("daysInMonth"));
                    if (monthDays == null) {
                        throw new IllegalArgumentException();
                    }
                    rule.append("BYMONTHDAY=" + monthDays + ";");
                }
            } else {
                throw new IllegalArgumentException("frequency is only supported for daily, weekly, and monthly.");
            }
        }
        return rule.toString();
    }

    private String translateWeekIntegersToDays(String expression) throws InvalidParameterException{
        StringBuilder daysResult = new StringBuilder();
        boolean[] daysAlreadyCounted = new boolean[7];
        String[] days = expression.split(",");
        int dayNumber;
        for (int i=0; i<days.length; i++) {
            dayNumber = Integer.parseInt(days[i]);
            dayNumber = dayNumber == 7 ? 0 : dayNumber;
            if (!daysAlreadyCounted[dayNumber]) {
                daysResult.append(dayNumberToDayOfWeekString(dayNumber) + ",");
                daysAlreadyCounted[dayNumber] = true;
            }
        }
        if (days.length == 0) {
            throw new InvalidParameterException("must have at least 1 day of the week if specifying repeating weekly");
        }
        daysResult.deleteCharAt(daysResult.length()-1);
        return daysResult.toString();
    }

    private String translateMonthIntegersToDays(String expression) throws InvalidParameterException {
        StringBuilder daysResult = new StringBuilder();
        boolean[] daysAlreadyCounted = new boolean[2*MAX_NUMBER_DAYS_IN_MONTH +1]; //for -31 to 31
        String[] days = expression.split(",");
        int dayNumber;
        for (int i=0; i<days.length; i++) {
            dayNumber = Integer.parseInt(days[i]);
            if (!daysAlreadyCounted[dayNumber+MAX_NUMBER_DAYS_IN_MONTH]) {
                daysResult.append(dayNumberToDayOfMonthString(dayNumber) + ",");
                daysAlreadyCounted[dayNumber+MAX_NUMBER_DAYS_IN_MONTH] = true;
            }
        }
        if (days.length == 0) {
            throw new InvalidParameterException("must have at least 1 day of the month if specifying repeating weekly");
        }
        daysResult.deleteCharAt(daysResult.length()-1);
        return daysResult.toString();
    }

    private String dayNumberToDayOfWeekString(int number) throws InvalidParameterException {
        String dayOfWeek = null;
        switch(number) {
            case 0: dayOfWeek="SU"; break;
            case 1: dayOfWeek="MO"; break;
            case 2: dayOfWeek="TU"; break;
            case 3: dayOfWeek="WE"; break;
            case 4: dayOfWeek="TH"; break;
            case 5: dayOfWeek="FR"; break;
            case 6: dayOfWeek="SA"; break;
            default: throw new InvalidParameterException("invalid day of week " + number);
        }
        return dayOfWeek;
    }

    private String dayNumberToDayOfMonthString(int number) throws InvalidParameterException {
        String dayOfMonth = null;
        // https://android.googlesource.com/platform/frameworks/opt/calendar/+/504844526f1b7afec048c6d2976ffb332670d5ba/src/com/android/calendarcommon2/EventRecurrence.java
        if(number != 0 && number >= -MAX_NUMBER_DAYS_IN_MONTH && number <= MAX_NUMBER_DAYS_IN_MONTH) {
            dayOfMonth = "" + number;
        } else {
            throw new InvalidParameterException("invalid day of month " + number);
        }
        return dayOfMonth;
    }

    private void swapViewWithPlaceholderView() {
        ViewGroup parent = (ViewGroup) getMraidView().getParent();
        if (parent == null) return;

        int index;
        int count = parent.getChildCount();
        for (index = 0; index < count; index++) {
            if (parent.getChildAt(index) == getMraidView()) break;
        }

        mViewIndexInParent = index;
        parent.addView(mPlaceholderView, index,
                new ViewGroup.LayoutParams(getMraidView().getWidth(), getMraidView().getHeight()));
        parent.removeView(getMraidView());
    }

    private void expandLayouts(View expansionContentView, int expandWidth, int expandHeight) {
        int closeButtonSize = (int) (CLOSE_BUTTON_SIZE_DP * mDensity + 0.5f);
        if (expandWidth < closeButtonSize) expandWidth = closeButtonSize;
        if (expandHeight < closeButtonSize) expandHeight = closeButtonSize;

        View dimmingView = new View(getContext());
        dimmingView.setBackgroundColor(Color.TRANSPARENT);
        dimmingView.setOnTouchListener(new OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });

        mExpansionLayout.addView(dimmingView, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.FILL_PARENT, RelativeLayout.LayoutParams.FILL_PARENT));

        mAdContainerLayout.addView(expansionContentView, new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.FILL_PARENT, RelativeLayout.LayoutParams.FILL_PARENT));

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(expandWidth, expandHeight);
        lp.addRule(RelativeLayout.CENTER_IN_PARENT);
        mExpansionLayout.addView(mAdContainerLayout, lp);
    }

    private void setOrientationLockEnabled(boolean enabled) {
        Context context = getContext();
        Activity activity = null;
        try {
            activity = (Activity) context;
            int requestedOrientation = enabled ?
                    activity.getResources().getConfiguration().orientation :
                    mOriginalRequestedOrientation;
            activity.setRequestedOrientation(requestedOrientation);
        } catch (ClassCastException e) {
            Log.d(LOGTAG, "Unable to modify device orientation.");
        }
    }

    protected void setNativeCloseButtonEnabled(boolean enabled) {
        if (mRootView == null) return;

        if (enabled) {
            if (mCloseButton == null) {
                StateListDrawable states = new StateListDrawable();
                states.addState(new int[] {-android.R.attr.state_pressed}, INTERSTITIAL_CLOSE_BUTTON_NORMAL.decodeImage(mRootView.getContext()));
                states.addState(new int[] {android.R.attr.state_pressed}, INTERSTITIAL_CLOSE_BUTTON_PRESSED.decodeImage(mRootView.getContext()));
                mCloseButton = new ImageButton(getContext());
                mCloseButton.setImageDrawable(states);
                mCloseButton.setBackgroundDrawable(null);
                mCloseButton.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        MraidDisplayController.this.close();
                    }
                });
            }

            int buttonSize = (int) (CLOSE_BUTTON_SIZE_DP * mDensity + 0.5f);
            FrameLayout.LayoutParams buttonLayout = new FrameLayout.LayoutParams(
                    buttonSize, buttonSize, Gravity.RIGHT);
            mAdContainerLayout.addView(mCloseButton, buttonLayout);
        } else {
            mAdContainerLayout.removeView(mCloseButton);
        }

        MraidView view = getMraidView();
        if (view.getOnCloseButtonStateChangeListener() != null) {
            view.getOnCloseButtonStateChangeListener().onCloseButtonStateChange(view, enabled);
        }
    }

    protected void useCustomClose(boolean shouldUseCustomCloseButton) {
        mAdWantsCustomCloseButton = shouldUseCustomCloseButton;

        MraidView view = getMraidView();
        boolean enabled = !shouldUseCustomCloseButton;
        if (view.getOnCloseButtonStateChangeListener() != null) {
            view.getOnCloseButtonStateChangeListener().onCloseButtonStateChange(view, enabled);
        }
    }

    protected boolean checkViewable() {
        return true;
    }

    FrameLayout createAdContainerLayout() {
        return new FrameLayout(getContext());
    }

    RelativeLayout createExpansionLayout() {
        return new RelativeLayout(getContext());
    }

    FrameLayout createPlaceholderView() {
        return new FrameLayout(getContext());
    }

    private Context getContext() {
        return getMraidView().getContext();
    }

    protected void initializeSupportedFunctionsProperty() {
        getMraidView().fireChangeEventForProperty(
                new MraidSupportsProperty()
                    .withSms(isSmsSupported())
                    .withTel(isTelSupported())
                    .withCalendar(true)
                    .withInlineVideo(true)
                    .withStorePicture(true));
    }

    private boolean isSmsSupported() {
        return hasPhone() && hasSmsPermission();
    }

    private boolean hasSmsPermission() {
        return getContext().checkCallingOrSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isTelSupported() {
        return hasPhone() && hasCallPermission();
    }

    private boolean hasCallPermission() {
        return getContext().checkCallingOrSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPhone() {
        return getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    private File getPictureStoragePath() {
        return new File(Environment.getExternalStorageDirectory(), "Pictures");
    }

    private String getFileNameForUriAndHttpResponse(final URI uri, final HttpResponse response) {
        final String path = uri.getPath();

        if (path == null) {
            return null;
        }

        String filename = new File(path).getName();

        Header header = response.getFirstHeader(MIME_TYPE_HEADER);
        if (header != null) {
            String[] fields = header.getValue().split(";");
            for (final String field : fields) {
                String extension;
                if (field.contains("image/")) {
                    extension = "." + field.split("/")[1];
                    if (!filename.endsWith(extension)) {
                        filename += extension;
                    }
                    break;
                }
            }
        }

        return filename;
    }

    private class MoPubMediaScannerConnectionClient implements MediaScannerConnection.MediaScannerConnectionClient {
        private final String mFilename;
        private final String mMimeType;
        private MediaScannerConnection mMediaScannerConnection;

        private MoPubMediaScannerConnectionClient(String filename, String mimeType) {
            mFilename = filename;
            mMimeType = mimeType;
        }

        private void setMediaScannerConnection(MediaScannerConnection connection) {
            mMediaScannerConnection = connection;
        }

        @Override
        public void onMediaScannerConnected() {
            if (mMediaScannerConnection != null) {
                mMediaScannerConnection.scanFile(mFilename, mMimeType);
            }
        }

        @Override
        public void onScanCompleted(String path, Uri uri) {
            if (mMediaScannerConnection != null) {
                mMediaScannerConnection.disconnect();
            }
        }
    }

    class OrientationBroadcastReceiver extends BroadcastReceiver {
        private int mLastRotation;
        private Context mContext;

        public void onReceive(Context context, Intent intent) {
            if(!isRegistered()) {
                return;
            }
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                int orientation = MraidDisplayController.this.getDisplayRotation();
                if (orientation != mLastRotation) {
                    mLastRotation = orientation;
                    MraidDisplayController.this.onOrientationChanged(mLastRotation);
                }
            }
        }

        private boolean isRegistered() {
            return mContext != null;
        }

        public void register(Context context) {
            mContext = context;
            context.registerReceiver(this,
                    new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
        }

        public void unregister() {
            mContext.unregisterReceiver(this);
            mContext = null;
        }
    }
}
