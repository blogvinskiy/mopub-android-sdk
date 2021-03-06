package com.mopub.mobileads;

import android.content.Context;
import android.content.res.Configuration;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import com.mopub.mobileads.util.DateAndTime;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.ConnectivityManager.*;
import static com.mopub.mobileads.AdUrlGenerator.MoPubNetworkType.ETHERNET;
import static com.mopub.mobileads.AdUrlGenerator.MoPubNetworkType.MOBILE;
import static com.mopub.mobileads.AdUrlGenerator.MoPubNetworkType.UNKNOWN;
import static com.mopub.mobileads.AdUrlGenerator.MoPubNetworkType.WIFI;

public class AdUrlGenerator extends BaseUrlGenerator {
    public static final String DEVICE_ORIENTATION_PORTRAIT = "p";
    public static final String DEVICE_ORIENTATION_LANDSCAPE = "l";
    public static final String DEVICE_ORIENTATION_SQUARE = "s";
    public static final String DEVICE_ORIENTATION_UNKNOWN = "u";
    public static final int UNKNOWN_NETWORK_TYPE = 0x00000008; // Equivalent to TYPE_DUMMY introduced in API level 14. Will generate the "unknown" code
    private Context mContext;
    private TelephonyManager mTelephonyManager;
    private ConnectivityManager mConnectivityManager;
    private String mAdUnitId;
    private String mKeywords;
    private Location mLocation;

    public static enum MoPubNetworkType {
        UNKNOWN,
        ETHERNET,
        WIFI,
        MOBILE;

        @Override
        public String toString() {
            return Integer.toString(ordinal());
        }
    }

    public AdUrlGenerator(Context context) {
        mContext = context;
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mConnectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public AdUrlGenerator withAdUnitId(String adUnitId) {
        mAdUnitId = adUnitId;
        return this;
    }

    public AdUrlGenerator withKeywords(String keywords) {
        mKeywords = keywords;
        return this;
    }

    public AdUrlGenerator withLocation(Location location) {
        mLocation = location;
        return this;
    }

    @Override
    public String generateUrlString(String serverHostname) {
        initUrlString(serverHostname, MoPubView.AD_HANDLER);

        setApiVersion("6");

        setAdUnitId(mAdUnitId);

        setSdkVersion(MoPub.SDK_VERSION);

        setUdid(getUdidFromContext(mContext));

        String keywords = AdUrlGenerator.addKeyword(mKeywords, AdUrlGenerator.getFacebookKeyword(mContext));
        setKeywords(keywords);

        setLocation(mLocation);

        setTimezone(AdUrlGenerator.getTimeZoneOffsetString());

        setOrientation(mContext.getResources().getConfiguration().orientation);

        setDensity(mContext.getResources().getDisplayMetrics().density);

        setMraidFlag(detectIsMraidSupported());

        String networkOperator = getNetworkOperator();
        setMccCode(networkOperator);
        setMncCode(networkOperator);

        setIsoCountryCode(mTelephonyManager.getNetworkCountryIso());
        setCarrierName(mTelephonyManager.getNetworkOperatorName());

        setNetworkType(getActiveNetworkType());

        setAppVersion(getAppVersionFromContext(mContext));

        return getFinalUrlString();
    }

    private void setAdUnitId(String adUnitId) {
        addParam("id", adUnitId);
    }

    private void setSdkVersion(String sdkVersion) {
        addParam("nv", sdkVersion);
    }

    private void setKeywords(String keywords) {
        addParam("q", keywords);
    }

    private void setLocation(Location location) {
        if (location != null) {
            addParam("ll", location.getLatitude() + "," + location.getLongitude());
            addParam("lla", "" + (int) location.getAccuracy());
        }
    }

    private void setTimezone(String timeZoneOffsetString) {
        addParam("z", timeZoneOffsetString);
    }

    private void setOrientation(int orientation) {
        String orString = DEVICE_ORIENTATION_UNKNOWN;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            orString = DEVICE_ORIENTATION_PORTRAIT;
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            orString = DEVICE_ORIENTATION_LANDSCAPE;
        } else if (orientation == Configuration.ORIENTATION_SQUARE) {
            orString = DEVICE_ORIENTATION_SQUARE;
        }
        addParam("o", orString);
    }

    private void setDensity(float density) {
        addParam("sc_a", "" + density);
    }

    private void setMraidFlag(boolean mraid) {
        if (mraid) addParam("mr", "1");
    }

    private void setMccCode(String networkOperator) {
        String mcc = networkOperator == null ? "" : networkOperator.substring(0, mncPortionLength(networkOperator));
        addParam("mcc", mcc);
    }

    private void setMncCode(String networkOperator) {
        String mnc = networkOperator == null ? "" : networkOperator.substring(mncPortionLength(networkOperator));
        addParam("mnc", mnc);
    }

    private void setIsoCountryCode(String networkCountryIso) {
        addParam("iso", networkCountryIso);
    }

    private void setCarrierName(String networkOperatorName) {
        addParam("cn", networkOperatorName);
    }

    private void setNetworkType(int type) {
        switch(type) {
            case TYPE_ETHERNET:
                addParam("ct", ETHERNET);
                break;
            case TYPE_WIFI:
                addParam("ct", WIFI);
                break;
            case TYPE_MOBILE:
            case TYPE_MOBILE_DUN:
            case TYPE_MOBILE_HIPRI:
            case TYPE_MOBILE_MMS:
            case TYPE_MOBILE_SUPL:
                addParam("ct", MOBILE);
                break;
            default:
                addParam("ct", UNKNOWN);
        }
    }

    private void addParam(String key, MoPubNetworkType value) {
        addParam(key, value.toString());
    }

    private boolean detectIsMraidSupported() {
        boolean mraid = true;
        try {
            Class.forName("com.mopub.mobileads.MraidView");
        } catch (ClassNotFoundException e) {
            mraid = false;
        }
        return mraid;
    }

    private String getNetworkOperator() {
        String networkOperator = mTelephonyManager.getNetworkOperator();
        if (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA &&
                mTelephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY) {
            networkOperator = mTelephonyManager.getSimOperator();
        }
        return networkOperator;
    }

    private int mncPortionLength(String networkOperator) {
        return Math.min(3, networkOperator.length());
    }

    private static String getTimeZoneOffsetString() {
        SimpleDateFormat format = new SimpleDateFormat("Z");
        format.setTimeZone(DateAndTime.localTimeZone());
        return format.format(DateAndTime.now());
    }

    private static String getFacebookKeyword(Context context) {
        try {
            Class<?> facebookKeywordProviderClass = Class.forName("com.mopub.mobileads.FacebookKeywordProvider");
            Method getKeywordMethod = facebookKeywordProviderClass.getMethod("getKeyword", Context.class);

            return (String) getKeywordMethod.invoke(facebookKeywordProviderClass, context);
        } catch (Exception exception) {
            return null;
        }
    }

    private int getActiveNetworkType() {
        if (mContext.checkCallingOrSelfPermission(ACCESS_NETWORK_STATE) == PERMISSION_GRANTED) {
            NetworkInfo activeNetworkInfo = mConnectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null ? activeNetworkInfo.getType() : UNKNOWN_NETWORK_TYPE;
        }
        return UNKNOWN_NETWORK_TYPE;
    }

    private static String addKeyword(String keywords, String addition) {
        if (addition == null || addition.length() == 0) {
            return keywords;
        } else if (keywords == null || keywords.length() == 0) {
            return addition;
        } else {
            return keywords + "," + addition;
        }
    }
}
