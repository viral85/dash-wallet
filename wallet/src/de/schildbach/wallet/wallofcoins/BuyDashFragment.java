package de.schildbach.wallet.wallofcoins;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.Loader;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;

import de.schildbach.wallet.BuyDashPref;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRatesProvider;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.service.BlockchainState;
import de.schildbach.wallet.service.BlockchainStateLoader;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.AddressAndLabel;
import de.schildbach.wallet.ui.CurrencyAmountView;
import de.schildbach.wallet.ui.CurrencyCalculatorLink;
import de.schildbach.wallet.ui.ExchangeRateLoader;
import de.schildbach.wallet.ui.WalletBalanceLoader;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import de.schildbach.wallet.wallofcoins.api.WallofCoins;
import de.schildbach.wallet.wallofcoins.response.BuyDashErrorResp;
import de.schildbach.wallet.wallofcoins.response.CaptureHoldResp;
import de.schildbach.wallet.wallofcoins.response.CheckAuthResp;
import de.schildbach.wallet.wallofcoins.response.ConfirmDepositResp;
import de.schildbach.wallet.wallofcoins.response.CountryData;
import de.schildbach.wallet.wallofcoins.response.CreateDeviceResp;
import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;
import de.schildbach.wallet.wallofcoins.response.DiscoveryInputsResp;
import de.schildbach.wallet.wallofcoins.response.GetAuthTokenResp;
import de.schildbach.wallet.wallofcoins.response.GetOffersResp;
import de.schildbach.wallet.wallofcoins.response.GetReceivingOptionsResp;
import de.schildbach.wallet.wallofcoins.response.OrderListResp;
import hashengineering.darkcoin.wallet.R;
import hashengineering.darkcoin.wallet.databinding.BuyDashFragmentBinding;
import hashengineering.darkcoin.wallet.databinding.ItemBankDetailBinding;
import hashengineering.darkcoin.wallet.databinding.ItemOrderListBinding;
import okhttp3.Interceptor;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static android.content.Context.LOCATION_SERVICE;


public final class BuyDashFragment extends Fragment implements OnSharedPreferenceChangeListener {
    private static final String TAG = BuyDashFragment.class.getSimpleName();
    private static final int ID_BALANCE_LOADER = 0;
    private static final int ID_RATE_LOADER = 1;
    private static final int ID_BLOCKCHAIN_STATE_LOADER = 2;
    private static final int ID_ADDRESS_LOADER = 4;
    private static final int PERMISSIONS_REQUEST_LOCATION = 8989;
    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private LoaderManager loaderManager;
    private Coin balance = null;
    private CurrencyCalculatorLink amountCalculatorLink;
    private BuyDashPref buyDashPref;
    private AddressAndLabel currentAddressQrAddress = null;


    public final int LAYOUT_CREATE_HOLD = 1;
    public final int LAYOUT_ORDER_LIST = 2;
    public final int LAYOUT_OFFERS = 3;
    public final int LAYOUT_VERIFY_OTP = 4;
    public final int LAYOUT_COMPLETION_DETAIL = 5;
    public final int LAYOUT_PHONE = 6;
    public final int LAYOUT_PASSWORD = 7;

    public ArrayList<Integer> backManageViews = new ArrayList<Integer>();

    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ExchangeRateLoader(activity, config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                final ExchangeRatesProvider.ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

                amountCalculatorLink.setExchangeRate(exchangeRate.rate);
                updateView();
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Coin> balanceLoaderCallbacks = new LoaderManager.LoaderCallbacks<Coin>() {
        @Override
        public Loader<Coin> onCreateLoader(final int id, final Bundle args) {
            return new WalletBalanceLoader(activity, wallet);
        }

        @Override
        public void onLoadFinished(final Loader<Coin> loader, final Coin balance) {
            BuyDashFragment.this.balance = balance;
            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<Coin> loader) {
        }
    };
    @Nullable
    private BlockchainState blockchainState = null;


    private final LoaderManager.LoaderCallbacks<BlockchainState> blockchainStateLoaderCallbacks = new LoaderManager.LoaderCallbacks<BlockchainState>() {
        @Override
        public Loader<BlockchainState> onCreateLoader(final int id, final Bundle args) {
            return new BlockchainStateLoader(activity);
        }

        @Override
        public void onLoadFinished(final Loader<BlockchainState> loader, final BlockchainState blockchainState) {
            BuyDashFragment.this.blockchainState = blockchainState;

            updateView();
        }

        @Override
        public void onLoaderReset(final Loader<BlockchainState> loader) {
        }
    };

    private final LoaderManager.LoaderCallbacks<Address> addressLoaderCallbacks = new LoaderManager.LoaderCallbacks<Address>() {
        @Override
        public Loader<Address> onCreateLoader(final int id, final Bundle args) {
            return new CurrentAddressLoader(activity, application.getWallet(), config);
        }

        @Override
        public void onLoadFinished(Loader<Address> loader, Address currentAddress) {
            if (!currentAddress.equals(currentAddressQrAddress)) {

                currentAddressQrAddress = new AddressAndLabel(currentAddress, config.getOwnName());

                addressStr = BitcoinURI.convertToBitcoinURI(currentAddressQrAddress.address, null, currentAddressQrAddress.label, null);
            }
        }

        @Override
        public void onLoaderReset(Loader<Address> loader) {

        }
    };

    private CreateHoldResp createHoldResp;
    private String offerId;
    private boolean isBuyMoreVisible;
    private String password;
    private List<GetReceivingOptionsResp> receivingOptionsResps;
    private String email = "";

    public static class CurrentAddressLoader extends AsyncTaskLoader<Address> {
        private LocalBroadcastManager broadcastManager;
        private final Wallet wallet;
        private Configuration config;

        private static final Logger log = LoggerFactory.getLogger(WalletBalanceLoader.class);

        public CurrentAddressLoader(final Context context, final Wallet wallet, final Configuration config) {
            super(context);

            this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
            this.wallet = wallet;
            this.config = config;
        }

        @Override
        protected void onStartLoading() {
            super.onStartLoading();

            wallet.addCoinsReceivedEventListener(Threading.SAME_THREAD, walletChangeListener);
            wallet.addCoinsSentEventListener(Threading.SAME_THREAD, walletChangeListener);
            wallet.addChangeEventListener(Threading.SAME_THREAD, walletChangeListener);
            broadcastManager.registerReceiver(walletChangeReceiver, new IntentFilter(WalletApplication.ACTION_WALLET_REFERENCE_CHANGED));
            config.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

            safeForceLoad();
        }

        @Override
        protected void onStopLoading() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();

            super.onStopLoading();
        }

        @Override
        protected void onReset() {
            config.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
            broadcastManager.unregisterReceiver(walletChangeReceiver);
            wallet.removeChangeEventListener(walletChangeListener);
            wallet.removeCoinsSentEventListener(walletChangeListener);
            wallet.removeCoinsReceivedEventListener(walletChangeListener);
            walletChangeListener.removeCallbacks();

            super.onReset();
        }

        @Override
        public Address loadInBackground() {
            org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

            return wallet.currentReceiveAddress();
        }

        private final ThrottlingWalletChangeListener walletChangeListener = new ThrottlingWalletChangeListener() {
            @Override
            public void onThrottledWalletChanged() {
                safeForceLoad();
            }
        };

        private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                safeForceLoad();
            }
        };

        private final OnSharedPreferenceChangeListener preferenceChangeListener = new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
                if (Configuration.PREFS_KEY_OWN_NAME.equals(key))
                    safeForceLoad();
            }
        };

        private void safeForceLoad() {
            try {
                forceLoad();
            } catch (final RejectedExecutionException x) {
                log.info("rejected execution: " + BuyDashFragment.CurrentAddressLoader.this.toString());
            }
        }
    }

    @Nullable
    private String defaultCurrency = null;
    private BuyDashFragmentBinding binding;

    private String token;
    private Interceptor interceptor = new Interceptor() {
        @Override
        public okhttp3.Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            // Request customization: add request headers
            Request.Builder requestBuilder = original.newBuilder()
                    .addHeader("X-Coins-Api-Token", buyDashPref.getAuthToken())
                    .addHeader("publisherId", addressStr);

            Request request = requestBuilder.build();
            return chain.proceed(request);
        }
    };

    private CountryData countryData;
    private String addressStr = "";

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);

        this.activity = (AbstractWalletActivity) context;
        this.application = (WalletApplication) activity.getApplication();
        this.config = application.getConfiguration();

        this.wallet = application.getWallet();
        this.loaderManager = getLoaderManager();

    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.buyDashPref = new BuyDashPref(PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext()));

        setRetainInstance(true);
        setHasOptionsMenu(true);

        password = getDeviceId(getContext());
        defaultCurrency = config.getExchangeCurrencyCode();
        config.registerOnSharedPreferenceChangeListener(this);
        buyDashPref.registerOnSharedPreferenceChangeListener(this);
    }

    String getDeviceId(Context context) {
        String deviceID = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        byte[] data = (deviceID + deviceID + deviceID).getBytes(Charsets.UTF_8);
        return Base64.encodeToString(data, Base64.DEFAULT).substring(0, 39);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_LOCATION: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    getZip();
                } else {
                    Toast.makeText(activity, "Location permission denied, \nYou can enable it from Settings", Toast.LENGTH_LONG).show();
                    binding.layoutLocation.setVisibility(View.GONE);
                    binding.layoutZip.setVisibility(View.VISIBLE);

                }
            }
        }
    }

    LocationManager mLocationManager;

    private Location getLastKnownLocation() {
        mLocationManager = (LocationManager) activity.getSystemService(LOCATION_SERVICE);
        List<String> providers = mLocationManager.getProviders(true);
        Location bestLocation = null;
        for (String provider : providers) {
            Location l = mLocationManager.getLastKnownLocation(provider);
            if (l == null) {
                continue;
            }
            if (bestLocation == null || l.getAccuracy() < bestLocation.getAccuracy()) {
                // Found best last known location: %s", l);
                bestLocation = l;
            }
        }
        return bestLocation;
    }

    private void requestLocation() {
        if (ContextCompat.checkSelfPermission(activity,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSIONS_REQUEST_LOCATION);
        } else {
            getZip();
        }
    }

    private void getReceivingOptions() {
        String locale;
        locale = getResources().getConfiguration().locale.getCountry();
        binding.linearProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(getActivity()).getReceivingOptions(locale.toLowerCase(), addressStr).enqueue(new Callback<List<GetReceivingOptionsResp>>() {
            @Override
            public void onResponse(Call<List<GetReceivingOptionsResp>> call, Response<List<GetReceivingOptionsResp>> response) {
                Log.e(TAG, "onResponse: " + response.body().size());
                binding.linearProgress.setVisibility(View.GONE);
                receivingOptionsResps = response.body();
                binding.linearPhone.setVisibility(View.GONE);
                binding.linearEmail.setVisibility(View.GONE);
                binding.linearPassword.setVisibility(View.GONE);
                binding.layoutVerifyOtp.setVisibility(View.GONE);
                binding.layoutZip.setVisibility(View.GONE);
                binding.layoutBanks.setVisibility(View.VISIBLE);

                //set data in drop down list
                setPaymentOptNames(receivingOptionsResps);
            }

            @Override
            public void onFailure(Call<List<GetReceivingOptionsResp>> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                t.printStackTrace();
            }
        });
    }

    private void setPaymentOptNames(final List<GetReceivingOptionsResp> receivingOptionsResps) {
        final ArrayList<String> names = new ArrayList<String>();
        GetReceivingOptionsResp optionsRespDefaultName = new GetReceivingOptionsResp();
        optionsRespDefaultName.name = "Select Payment Center";
        receivingOptionsResps.add(0, optionsRespDefaultName);
        for (GetReceivingOptionsResp receivingOptionsResp : receivingOptionsResps) {
            names.add((receivingOptionsResp.name));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spBanks.setAdapter(adapter);

        binding.spBanks.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) return;
                bankId = "" + receivingOptionsResps.get(position - 1).id;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

    }

    public boolean isValidEmail(CharSequence target) {
        if (target == null) {
            return false;
        } else {
            return android.util.Patterns.EMAIL_ADDRESS.matcher(target).matches();
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {

        binding = DataBindingUtil.inflate(inflater, R.layout.buy_dash_fragment, container, false);

        binding.buttonBuyDashGetLocation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestLocation();
            }
        });

        binding.buttonBuyDashGetLocationNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                binding.layoutLocation.setVisibility(View.GONE);
                binding.layoutZip.setVisibility(View.VISIBLE);
            }
        });

        binding.buttonBuyDashZipNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (TextUtils.isEmpty(binding.buyDashZip.getText())) {
                    getReceivingOptions();
                } else {
                    binding.layoutZip.setVisibility(View.GONE);
                    binding.layoutCreateHold.setVisibility(View.VISIBLE);
                    showKeyBoard();
                }
            }
        });

        binding.buttonBuyDashBankNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (binding.spBanks.getSelectedItemPosition() == 0) {
                    Toast.makeText(activity, "Select Any Payment Center", Toast.LENGTH_LONG).show();
                } else {
                    callDiscoveryInputs();
                }
            }
        });

        binding.btnNextEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!binding.editBuyDashEmail.getText().toString().isEmpty() && isValidEmail(binding.editBuyDashEmail.getText().toString())) {
                    email = binding.editBuyDashEmail.getText().toString();
                    binding.linearEmail.setVisibility(View.GONE);
                    binding.linearPhone.setVisibility(View.VISIBLE);
                } else {
                    Toast.makeText(activity, "Enter Valid Email or click [Do Not send me email] to skip", Toast.LENGTH_LONG).show();
                }
            }
        });

        binding.tvSkipEmail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                binding.linearEmail.setVisibility(View.GONE);
                binding.linearPhone.setVisibility(View.VISIBLE);
            }
        });

        String json;
        try {
            InputStream is = activity.getAssets().open("countries.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }

        countryData = new Gson().fromJson(json, CountryData.class);

        List<String> stringList = new ArrayList<>();

        for (CountryData.CountriesBean bean : countryData.countries) {
            stringList.add(bean.name + " (" + bean.code + ")");
        }

        CustomAdapter customAdapter = new CustomAdapter(activity, R.layout.spinner_row_country, countryData.countries);
        customAdapter.setDropDownViewResource(R.layout.spinner_row_country);
        binding.spCountry.setAdapter(customAdapter);

        if (config.getFormat().code().equals("DASH")) {
            binding.btnBuyMore.setText("Buy More Dash!");
        } else {
            binding.btnBuyMore.setText("Buy More Bitcoin!");
        }
        binding.requestCoinsAmountBtc.setCurrencySymbol(config.getFormat().code());
        binding.requestCoinsAmountBtc.setInputFormat(config.getMaxPrecisionFormat());
        binding.requestCoinsAmountBtc.setHintFormat(config.getFormat());

        binding.requestCoinsAmountLocal.setInputFormat(Constants.LOCAL_FORMAT);
        binding.requestCoinsAmountLocal.setHintFormat(Constants.LOCAL_FORMAT);
        amountCalculatorLink = new CurrencyCalculatorLink(binding.requestCoinsAmountBtc, binding.requestCoinsAmountLocal);

        binding.rvOffers.setLayoutManager(new LinearLayoutManager(activity));

        getOrderList(false);
        binding.buttonBuyDashGetOffers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyBoard();
                if (Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) > 0f || !TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getText())) {
                    if (!TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getText().toString()) && Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getText().toString()) >= 5f
                            || !TextUtils.isEmpty(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) && Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) >= 5f) {
                        callDiscoveryInputs();
                    } else {
                        Toast.makeText(activity, "Purchase amount must be at least $5", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getContext(), "Enter amount", Toast.LENGTH_SHORT).show();
                }
            }
        });

        binding.buttonVerifyOtp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyBoard();
                HashMap<String, String> captureHoldReq = new HashMap<String, String>();
                String otp = binding.etOtp.getText().toString().trim();
                if (TextUtils.isEmpty(otp)) {
                    Toast.makeText(getContext(), "Please Enter Purchase Code!", Toast.LENGTH_LONG).show();
                    return;
                }
                captureHoldReq.put("publisherId", addressStr);
                captureHoldReq.put("verificationCode", otp);
                Log.d(TAG, "onClick: authToken==>>" + buyDashPref.getAuthToken());
                binding.linearProgress.setVisibility(View.VISIBLE);
                WallofCoins.createService(interceptor, getActivity()).captureHold(buyDashPref.getHoldId(), captureHoldReq)
                        .enqueue(new Callback<List<CaptureHoldResp>>() {
                            @Override
                            public void onResponse(Call<List<CaptureHoldResp>> call, final Response<List<CaptureHoldResp>> response) {
                                binding.linearProgress.setVisibility(View.GONE);
                                if (null != response && null != response.body() && !response.body().isEmpty()) {
                                    if (response.body().get(0).account != null && !TextUtils.isEmpty(response.body().get(0).account)) {
                                        if (isJSONValid(response.body().get(0).account)) {
                                            ItemBankDetailBinding itemBankBinding = DataBindingUtil.inflate(LayoutInflater.from(activity), R.layout.item_bank_detail, null, false);
                                            Glide.with(activity).load(response.body().get(0).bankLogo).into(itemBankBinding.imageBank);
                                            if (response.body().get(0).bankName != null && !TextUtils.isEmpty(response.body().get(0).bankName)) {
                                                itemBankBinding.textBankName.setText(response.body().get(0).bankName);
                                            } else {
                                                itemBankBinding.textBankName.setVisibility(View.GONE);
                                            }
                                            if (response.body().get(0).nearestBranch.phone != null && !TextUtils.isEmpty(response.body().get(0).nearestBranch.phone)) {
                                                itemBankBinding.textPhone.setText("Phone: " + response.body().get(0).nearestBranch.phone);
                                            } else {
                                                itemBankBinding.textPhone.setVisibility(View.GONE);
                                            }

                                            if (response.body().get(0).nameOnAccount != null && !TextUtils.isEmpty(response.body().get(0).nameOnAccount)) {
                                                itemBankBinding.textNameAccount.setText("Name on Account: " + response.body().get(0).nameOnAccount);
                                            } else {
                                                itemBankBinding.textNameAccount.setVisibility(View.GONE);
                                            }

                                            if (response.body().get(0).payment != null && !TextUtils.isEmpty(response.body().get(0).payment)) {
                                                itemBankBinding.textCashToDeposite.setText("Cash to Deposit: " + response.body().get(0).payment);
                                            } else {
                                                itemBankBinding.textCashToDeposite.setVisibility(View.GONE);
                                            }

                                            if (response.body().get(0).paymentDue != null && !TextUtils.isEmpty(response.body().get(0).paymentDue)) {
                                                itemBankBinding.textDepositeDue.setText("Deposit Due: " + response.body().get(0).paymentDue.substring(0, 16).replace("T", " "));
                                            } else {
                                                itemBankBinding.textDepositeDue.setVisibility(View.GONE);
                                            }
                                            Type listType = new TypeToken<ArrayList<AccountJson>>() {
                                            }.getType();
                                            ArrayList<AccountJson> accountList = new Gson().fromJson(response.body().get(0).account, listType);
                                            for (int i = 0; i < accountList.size(); i++) {
                                                TextView textView = new TextView(getActivity());
                                                textView.setTextSize(16);
                                                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                                                layoutParams.topMargin = 8;
                                                textView.setLayoutParams(layoutParams);
                                                textView.setText(accountList.get(i).getLabel() + ": " + accountList.get(i).getValue());
                                                itemBankBinding.linearAccountDetail.addView(textView);
                                            }
                                            binding.layoutCompletionDetail.removeAllViews();
                                            binding.layoutCompletionDetail.addView(itemBankBinding.getRoot());

                                            if (response.body().get(0).status.equals("WD")) {
                                                itemBankBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                                                itemBankBinding.btnDepositFinished.setVisibility(View.VISIBLE);
                                            } else {
                                                itemBankBinding.btnCancelOrder.setVisibility(View.GONE);
                                                itemBankBinding.btnDepositFinished.setVisibility(View.GONE);
                                            }

                                            itemBankBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    hideKeyBoard();
                                                    confirmDeposit(response.body().get(0));
                                                }
                                            });

                                            itemBankBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    hideKeyBoard();
                                                    // call cancel order
                                                    cancelOrder("" + response.body().get(0).id);
                                                }
                                            });
                                        } else {
                                            binding.setConfiremedData(response.body().get(0));
                                        }
                                    } else {
                                        binding.setConfiremedData(response.body().get(0));
                                    }
                                    binding.layoutVerifyOtp.setVisibility(View.GONE);
                                    binding.layoutCompletionDetail.setVisibility(View.VISIBLE);
                                    backManageViews.add(LAYOUT_COMPLETION_DETAIL);

                                    switch (response.body().get(0).status) {
                                        case "WD":
                                            binding.orderStatus.setText("Status: Waiting Deposit");
                                            break;
                                        case "WDV":
                                            binding.orderStatus.setText("Status: Waiting Deposit Verification");
                                            break;
                                        case "RERR":
                                            binding.orderStatus.setText("Status: Issue w/ Receipt, Contacted Buyer");
                                            break;
                                        case "DERR":
                                            binding.orderStatus.setText("Status: Issue with Deposit, Needs Follow-up");
                                            break;
                                        case "RSD":
                                            binding.orderStatus.setText("Status: Reserved for Seller Deposit");
                                            break;
                                        case "RMIT":
                                            binding.orderStatus.setText("Status: Remit Address Missing");
                                            break;
                                        case "UCRV":
                                            binding.orderStatus.setText("Status: Under Compliance Review");
                                            break;
                                        case "PAYP":
                                            binding.orderStatus.setText("Status: Done - Pending Delivery");
                                            break;
                                        case "SENT":
                                            binding.orderStatus.setText("Status: Done - Units Delivered");
                                            break;
                                        case "CANC":
                                            binding.orderStatus.setText("Status: Buyer Canceled");
                                            break;
                                        case "ACAN":
                                            binding.orderStatus.setText("Status: Staff Canceled");
                                            break;
                                        case "EXP":
                                            binding.orderStatus.setText("Status: Deposit Time Expired");
                                            break;
                                        case "ML":
                                            binding.orderStatus.setText("Status: Meat Locker");
                                            break;
                                        case "MLR":
                                            binding.orderStatus.setText("Status: Meat Locker Returned");
                                            break;
                                    }
                                    binding.textNearByCenter.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            hideKeyBoard();
                                            String yourAddress = response.body().get(0).nearestBranch.name
                                                    + ", " + response.body().get(0).nearestBranch.address
                                                    + ", " + response.body().get(0).nearestBranch.city
                                                    + ", " + response.body().get(0).nearestBranch.state;
                                            String uri = "http://maps.google.co.in/maps?q=" + yourAddress;
                                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                                            activity.startActivity(intent);
                                        }
                                    });
                                    binding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            hideKeyBoard();
                                            confirmDeposit(response.body().get(0));
                                        }
                                    });

                                    binding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            hideKeyBoard();
                                            // call cancel order
                                            cancelOrder("" + response.body().get(0).id);
                                        }
                                    });

                                } else if (null != response && null != response.errorBody()) {
                                    binding.linearProgress.setVisibility(View.GONE);
                                    try {
                                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    }
                                } else {
                                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    binding.linearProgress.setVisibility(View.GONE);
                                }
                            }

                            @Override
                            public void onFailure
                                    (Call<List<CaptureHoldResp>> call, Throwable t) {
                                binding.linearProgress.setVisibility(View.GONE);
                                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                Log.e(TAG, "onFailure: ", t);
                            }
                        });
            }
        });

        return binding.getRoot();
    }

    private void getZip() {

        Location myLocation = getLastKnownLocation();
        Geocoder geocoder;
        List<android.location.Address> addresses;
        geocoder = new Geocoder(activity, Locale.getDefault());

        try {
            addresses = geocoder.getFromLocation(myLocation.getLatitude(), myLocation.getLongitude(), 1); // Here 1 represent max location result to returned, by documents it recommended 1 to 5
            String address = addresses.get(0).getAddressLine(0); // If any additional address line present than only, check with max available address lines by getMaxAddressLineIndex()
            String city = addresses.get(0).getLocality();
            String state = addresses.get(0).getAdminArea();
            String country = addresses.get(0).getCountryName();
            String postalCode = addresses.get(0).getPostalCode();
            String knownName = addresses.get(0).getFeatureName();

            binding.buyDashZip.setText(postalCode);
            binding.layoutLocation.setVisibility(View.GONE);
            binding.layoutCreateHold.setVisibility(View.VISIBLE);
            showKeyBoard();

//            Toast.makeText(activity, "Location permission granted zip " + postalCode, Toast.LENGTH_LONG).show();


        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isJSONValid(String test) {
        try {
            new JSONObject(test);
        } catch (JSONException ex) {
            // edited, to include @Arthur's comment
            // e.g. in case JSONArray is valid as well...
            try {
                new JSONArray(test);
            } catch (JSONException ex1) {
                return false;
            }
        }
        return true;
    }

    private void cancelOrder(String orderId) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, activity).cancelOrder(orderId, addressStr).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                binding.linearProgress.setVisibility(View.GONE);
                if (response.code() == 204) {
                    Toast.makeText(getContext(), "Your Order cancelled successfully", Toast.LENGTH_SHORT).show();
                    activity.finish();
                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "onFailure: ", t);
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDeposit(CaptureHoldResp response) {
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).confirmDeposit("" + response.id, "", addressStr).enqueue(new Callback<ConfirmDepositResp>() {
            @Override
            public void onResponse(Call<ConfirmDepositResp> call, Response<ConfirmDepositResp> response) {
                binding.linearProgress.setVisibility(View.GONE);

                if (null != response && null != response.body()) {
                    binding.layoutCompletionDetail.setVisibility(View.GONE);
                    Toast.makeText(getContext(), "Your order is confirmed successfully", Toast.LENGTH_SHORT).show();
                    activity.finish();
                } else if (null != response && null != response.errorBody()) {
                    try {
                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }

                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<ConfirmDepositResp> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());
        amountCalculatorLink.requestFocus();
    }

    @Override
    public void onResume() {
        super.onResume();

        amountCalculatorLink.setListener(new CurrencyAmountView.Listener() {
            @Override
            public void changed() {
                updateView();
            }

            @Override
            public void focusChanged(final boolean hasFocus) {
            }
        });
        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);

        loaderManager.initLoader(ID_BALANCE_LOADER, null, balanceLoaderCallbacks);
        loaderManager.initLoader(ID_BLOCKCHAIN_STATE_LOADER, null, blockchainStateLoaderCallbacks);
        loaderManager.initLoader(ID_ADDRESS_LOADER, null, addressLoaderCallbacks);

        updateView();
    }

    @Override
    public void onPause() {

        loaderManager.destroyLoader(ID_BALANCE_LOADER);
        loaderManager.destroyLoader(ID_BLOCKCHAIN_STATE_LOADER);
        loaderManager.destroyLoader(ID_ADDRESS_LOADER);

        amountCalculatorLink.setListener(null);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        config.unregisterOnSharedPreferenceChangeListener(this);

        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
        loaderManager.destroyLoader(ID_RATE_LOADER);

        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key) || Configuration.PREFS_KEY_BTC_PRECISION.equals(key)) {
            defaultCurrency = config.getExchangeCurrencyCode();

            updateView();
        }
    }

    private void updateView() {
        balance = application.getWallet().getBalance(BalanceType.ESTIMATED);
    }

    private String bankId = "";

    private void callDiscoveryInputs() {

        HashMap<String, String> discoveryInputsReq = new HashMap<String, String>();

        discoveryInputsReq.put("publisherId", addressStr);
        discoveryInputsReq.put("publisherId", "47");
        try {
            if (Float.valueOf(binding.requestCoinsAmountLocal.getTextView().getHint().toString()) > 0f) {
                discoveryInputsReq.put("usdAmount", "" + binding.requestCoinsAmountLocal.getTextView().getHint());
            } else {
                discoveryInputsReq.put("usdAmount", "" + binding.requestCoinsAmountLocal.getTextView().getText());
            }
            Log.d(TAG, "callDiscoveryInputs: usdAmount==>>" + binding.requestCoinsAmountLocal.getTextView().getHint());
        } catch (Exception e) {
            discoveryInputsReq.put("usdAmount", "0");
            e.printStackTrace();
        }
        discoveryInputsReq.put("crypto", config.getFormat().code());
        discoveryInputsReq.put("bank", bankId);
        discoveryInputsReq.put("zipCode", binding.buyDashZip.getText().toString());

        binding.linearProgress.setVisibility(View.VISIBLE);

        WallofCoins.createService(getActivity()).discoveryInputs(discoveryInputsReq).enqueue(new Callback<DiscoveryInputsResp>() {
            @Override
            public void onResponse(Call<DiscoveryInputsResp> call, Response<DiscoveryInputsResp> response) {

                if (null != response && null != response.body()) {
                    if (null != response.body().id) {
                        WallofCoins.createService(getActivity()).getOffers(response.body().id, addressStr).enqueue(new Callback<GetOffersResp>() {
                            @Override
                            public void onResponse(Call<GetOffersResp> call, final Response<GetOffersResp> response) {

                                if (null != response && null != response.body()) {

                                    binding.linearProgress.setVisibility(View.GONE);

                                    if (null != response.body().singleDeposit && !response.body().singleDeposit.isEmpty()) {
                                        binding.layoutCreateHold.setVisibility(View.GONE);
                                        binding.linearPhone.setVisibility(View.GONE);
                                        binding.linearEmail.setVisibility(View.GONE);
                                        binding.linearPassword.setVisibility(View.GONE);
                                        binding.layoutCompletionDetail.setVisibility(View.GONE);
                                        binding.rvOrderList.setVisibility(View.GONE);
                                        binding.layoutVerifyOtp.setVisibility(View.GONE);
                                        binding.rvOffers.setVisibility(View.VISIBLE);
                                        backManageViews.add(LAYOUT_OFFERS);
                                        BuyDashOffersAdapter buyDashOffersAdapter = new BuyDashOffersAdapter(activity, response.body().singleDeposit, response.body().doubleDeposit, new AdapterView.OnItemSelectedListener() {
                                            @Override
                                            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                                                hideKeyBoard();
                                                if (position < response.body().singleDeposit.size() + 1) {
                                                    offerId = response.body().singleDeposit.get(position - 1).id;
                                                } else {
                                                    offerId = response.body().doubleDeposit.get(position - response.body().singleDeposit.size() - 2).id;
                                                }
                                                if (buyDashPref.getAuthToken() != null && !TextUtils.isEmpty(buyDashPref.getAuthToken())) {
                                                    binding.editBuyDashPhone.setText(buyDashPref.getPhone());
                                                    binding.editBuyDashEmail.setText(buyDashPref.getPhone());
                                                    createHold(true);
                                                } else {
                                                    binding.rvOffers.setVisibility(View.GONE);
                                                    binding.linearEmail.setVisibility(View.VISIBLE);
                                                    backManageViews.add(LAYOUT_PHONE);
                                                    binding.btnNextPhone.setOnClickListener(new View.OnClickListener() {
                                                        @Override
                                                        public void onClick(View v) {
                                                            hideKeyBoard();
                                                            checkAuth();
                                                        }
                                                    });
                                                }
                                            }

                                            @Override
                                            public void onNothingSelected(AdapterView<?> parent) {
                                                binding.linearProgress.setVisibility(View.GONE);
                                            }
                                        });
                                        binding.rvOffers.setAdapter(buyDashOffersAdapter);
                                    } else {
                                        Toast.makeText(getContext(), "No offers available", Toast.LENGTH_LONG).show();
                                    }
                                } else if (null != response && null != response.errorBody()) {
                                    binding.linearProgress.setVisibility(View.GONE);
                                    try {
                                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                                        Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                    }

                                } else {
                                    binding.linearProgress.setVisibility(View.GONE);
                                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                                }
                            }

                            @Override
                            public void onFailure(Call<GetOffersResp> call, Throwable t) {
                                binding.linearProgress.setVisibility(View.GONE);
                                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                            }
                        });
                    } else {
                        binding.linearProgress.setVisibility(View.GONE);
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }
                } else if (null != response && null != response.errorBody()) {

                    binding.linearProgress.setVisibility(View.GONE);

                    try {
                        BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                        if (buyDashErrorResp.detail != null && !TextUtils.isEmpty(buyDashErrorResp.detail)) {
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }

                } else {
                    binding.linearProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<DiscoveryInputsResp> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void getAuthTokenCall(boolean isPass) {
        String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
        String phone = countryCode + binding.editBuyDashPhone.getText().toString().trim();
//        String deviceCode = binding.etPassword.getText().toString().trim();

        if (!TextUtils.isEmpty(phone)) {
//            final GetAuthTokenReq getAuthTokenReq = new GetAuthTokenReq();

            HashMap<String, String> getAuthTokenReq = new HashMap<String, String>();
            getAuthTokenReq.put(isPass ? "password" : "deviceCode", password);
            getAuthTokenReq.put("publisherId", addressStr);


//            getAuthTokenReq.deviceCode = password;

            binding.linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(getActivity()).getAuthToken(phone, getAuthTokenReq).enqueue(new Callback<GetAuthTokenResp>() {
                @Override
                public void onResponse(Call<GetAuthTokenResp> call, Response<GetAuthTokenResp> response) {
                    binding.linearProgress.setVisibility(View.GONE);
                    int code = response.code();

                    if (code >= 400 && response.body() == null) {
                        try {
                            BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                        return;
                    }

                    buyDashPref.setAuthToken(response.body().token);
                    buyDashPref.setPhone(response.body().phone);
                    buyDashPref.setEmail(response.body().email);
                    binding.linearPassword.setVisibility(View.GONE);
                    // call create hold

                    creteDevice();
                }

                @Override
                public void onFailure(Call<GetAuthTokenResp> call, Throwable t) {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                    binding.linearProgress.setVisibility(View.GONE);
                }
            });

        } else {
            Toast.makeText(getContext(), "Phone and Password is required", Toast.LENGTH_SHORT).show();
        }
    }

    private void creteDevice() {
        password = getDeviceId(getContext());
        final HashMap<String, String> createDeviceReq = new HashMap<String, String>();
        createDeviceReq.put("name", "Dash Wallet (Android)");
        createDeviceReq.put("code", password);
        createDeviceReq.put("publisherId", addressStr);
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, getActivity()).createDevice(createDeviceReq).enqueue(new Callback<CreateDeviceResp>() {
            @Override
            public void onResponse(Call<CreateDeviceResp> call, Response<CreateDeviceResp> response) {
                if (response.code() < 299) {
                    createHold(true);
                } else {
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<CreateDeviceResp> call, Throwable t) {
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void createHold(boolean isUserExist) {
        String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
        String phone = countryCode + binding.editBuyDashPhone.getText().toString().trim();
//        String deviceCode = binding.etPassword.getText().toString().trim();

        if (!TextUtils.isEmpty(phone) && !TextUtils.isEmpty(password)) {
            final HashMap<String, String> createHoldPassReq = new HashMap<String, String>();

            if (!isUserExist) createHoldPassReq.put("phone", phone);
            createHoldPassReq.put("offer", offerId);
            createHoldPassReq.put("publisherId", addressStr);
            createHoldPassReq.put("email", email);
            createHoldPassReq.put("deviceName", "Dash Wallet (Android)");
            createHoldPassReq.put("deviceCode", password);
//            createHoldPassReq.put("deviceCode", password);
            binding.linearProgress.setVisibility(View.VISIBLE);
            WallofCoins.createService(interceptor, getActivity()).createHold(createHoldPassReq).enqueue(new Callback<CreateHoldResp>() {
                @Override
                public void onResponse(Call<CreateHoldResp> call, Response<CreateHoldResp> response) {
                    binding.linearProgress.setVisibility(View.GONE);

                    if (response.code() == 403) {
                        binding.layoutCreateHold.setVisibility(View.GONE);
                        binding.linearEmail.setVisibility(View.VISIBLE);
                        backManageViews.add(LAYOUT_PHONE);
                        binding.linearPassword.setVisibility(View.GONE);
                        binding.layoutCompletionDetail.setVisibility(View.GONE);
                        binding.rvOrderList.setVisibility(View.GONE);
                        binding.layoutVerifyOtp.setVisibility(View.GONE);
                        binding.rvOffers.setVisibility(View.GONE);

                        binding.btnNextPhone.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                hideKeyBoard();
                                checkAuth();
                            }
                        });
                        return;
                    }
                    if (response.code() == 404) {
                        AlertDialog.Builder builder;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            builder = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert);
                        } else {
                            builder = new AlertDialog.Builder(activity);
                        }
                        builder.setTitle("Whoops!")
                                .setMessage("The Purchase Code has expired. " +
                                        "After you receive the Purchase Code, you will need to type quickly. " +
                                        "You will need to create another order to receive another Purchase Code.")
                                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        binding.layoutCompletionDetail.setVisibility(View.GONE);
                                        binding.rvOffers.setVisibility(View.GONE);
                                        binding.rvOrderList.setVisibility(View.GONE);
                                        binding.linearPhone.setVisibility(View.GONE);
                                        binding.linearEmail.setVisibility(View.GONE);
                                        binding.linearPassword.setVisibility(View.GONE);
                                        binding.layoutVerifyOtp.setVisibility(View.GONE);
                                        binding.layoutLocation.setVisibility(View.GONE);
                                        binding.layoutCreateHold.setVisibility(View.VISIBLE);
                                    }
                                })
                                .show();
                        return;
                    }
                    if (null != response.body()) {
                        createHoldResp = response.body();
                        buyDashPref.setHoldId(createHoldResp.id);
                        buyDashPref.setCreateHoldResp(createHoldResp);
                        buyDashPref.setAuthToken(createHoldResp.token);
//                        getAuthTokenCall(false);
                        binding.layoutCreateHold.setVisibility(View.GONE);
                        binding.linearPhone.setVisibility(View.GONE);
                        binding.linearEmail.setVisibility(View.GONE);
                        binding.linearPassword.setVisibility(View.GONE);
                        binding.layoutCompletionDetail.setVisibility(View.GONE);
                        binding.rvOrderList.setVisibility(View.GONE);
                        binding.layoutVerifyOtp.setVisibility(View.GONE);
                        binding.rvOffers.setVisibility(View.GONE);

                        buyDashPref.setAuthToken(response.body().token);
                        // call create hold

                        binding.layoutVerifyOtp.setVisibility(View.VISIBLE);
                        backManageViews.add(LAYOUT_VERIFY_OTP);


//                        Log.d(TAG, "onResponse: purchase code==>>" + createHoldResp.__PURCHASE_CODE);
                        binding.etOtp.setText(createHoldResp.__PURCHASE_CODE);
                    } else if (null != response.errorBody()) {
                        try {
                            BuyDashErrorResp buyDashErrorResp = new Gson().fromJson(response.errorBody().string(), BuyDashErrorResp.class);
                            Log.d(TAG, "onResponse: message==>> " + buyDashErrorResp.detail);
                            getOrderList(false);
                            Toast.makeText(getContext(), buyDashErrorResp.detail, Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onFailure(Call<CreateHoldResp> call, Throwable t) {
                    binding.linearProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(getContext(), "Create Password", Toast.LENGTH_SHORT).show();
        }

    }

    public void getOrderList(final boolean isFromCreateHold) {
        Log.d(TAG, "getOrderList: " + buyDashPref.getAuthToken());
        binding.linearProgress.setVisibility(View.VISIBLE);
        WallofCoins.createService(interceptor, activity).getOrders(addressStr).enqueue(new Callback<List<OrderListResp>>() {
            @Override
            public void onResponse(Call<List<OrderListResp>> call, Response<List<OrderListResp>> response) {
                binding.linearProgress.setVisibility(View.GONE);
                if (response.code() == 200 && response.body() != null) {
                    Log.d(TAG, "onResponse: boolean==>" + isFromCreateHold);
                    if (isFromCreateHold) {
                        List<OrderListResp> orderList = response.body();
                        if (orderList != null && orderList.size() > 0) {
                            for (OrderListResp orderListResp : orderList) {
                                if (orderListResp.status.equals("WD")) {
                                    Log.d(TAG, "onResponse: status==>" + orderListResp.status);
                                    manageOrderList(response);
                                    break;
                                } else {
                                    Log.d(TAG, "onResponse: status2==>" + orderListResp.status);
                                    binding.layoutCreateHold.setVisibility(View.GONE);
                                    binding.linearPhone.setVisibility(View.GONE);
                                    binding.linearEmail.setVisibility(View.GONE);
                                    binding.linearPassword.setVisibility(View.GONE);
                                    binding.layoutCompletionDetail.setVisibility(View.GONE);
                                    binding.rvOrderList.setVisibility(View.GONE);
                                    binding.layoutVerifyOtp.setVisibility(View.VISIBLE);
                                    backManageViews.add(LAYOUT_VERIFY_OTP);
                                    binding.rvOffers.setVisibility(View.GONE);
                                }
                            }
                        } else {
                            binding.layoutCreateHold.setVisibility(View.GONE);
                            binding.linearPhone.setVisibility(View.GONE);
                            binding.linearEmail.setVisibility(View.GONE);
                            binding.linearPassword.setVisibility(View.GONE);
                            binding.layoutCompletionDetail.setVisibility(View.GONE);
                            binding.rvOrderList.setVisibility(View.GONE);
                            binding.layoutVerifyOtp.setVisibility(View.VISIBLE);
                            backManageViews.add(LAYOUT_VERIFY_OTP);
                            binding.rvOffers.setVisibility(View.GONE);
                        }
                    } else {
                        manageOrderList(response);
                    }
                } else if (response.code() == 403) {
                    binding.layoutLocation.setVisibility(View.VISIBLE);
                    backManageViews.add(LAYOUT_CREATE_HOLD);
                    binding.linearPhone.setVisibility(View.GONE);
                    binding.linearEmail.setVisibility(View.GONE);
                    binding.linearPassword.setVisibility(View.GONE);
                    binding.layoutCompletionDetail.setVisibility(View.GONE);
                    binding.rvOrderList.setVisibility(View.GONE);
                    binding.layoutVerifyOtp.setVisibility(View.GONE);
                    binding.rvOffers.setVisibility(View.GONE);
                }
            }

            @Override
            public void onFailure(Call<List<OrderListResp>> call, Throwable t) {
                binding.linearProgress.setVisibility(View.GONE);
                Log.e(TAG, "onFailure: ", t);
                Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void manageOrderList(Response<List<OrderListResp>> response) {
        Log.d(TAG, "manageOrderList: list-==" + backManageViews.toString());
        List<OrderListResp> orderList = response.body();
        List<OrderListResp> orderListTemp = new ArrayList<OrderListResp>();
        orderListTemp.addAll(orderList);

        if (orderList != null && orderList.size() > 0) {
            binding.layoutCreateHold.setVisibility(View.GONE);
            binding.linearPhone.setVisibility(View.GONE);
            binding.linearEmail.setVisibility(View.GONE);
            binding.linearPassword.setVisibility(View.GONE);
            binding.layoutCompletionDetail.setVisibility(View.GONE);
            binding.rvOrderList.setVisibility(View.VISIBLE);
            backManageViews.add(LAYOUT_ORDER_LIST);
            binding.layoutVerifyOtp.setVisibility(View.GONE);
            binding.rvOffers.setVisibility(View.GONE);
            for (OrderListResp orderListResp : orderList) {
                if (orderListResp.status.equals("WD")) {
                    binding.btnBuyMore.setVisibility(View.GONE);
                    isBuyMoreVisible = false;
                    break;
                } else {
                    binding.btnBuyMore.setVisibility(View.VISIBLE);
                    isBuyMoreVisible = true;
                }
            }
            binding.btnBuyMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    binding.rvOrderList.setVisibility(View.GONE);
                    binding.btnBuyMore.setVisibility(View.GONE);
                    binding.requestCoinsAmountBtcEdittext.setText("");
                    binding.requestCoinsAmountLocalEdittext.setText("");
                    binding.buyDashZip.setText("");
                    binding.layoutLocation.setVisibility(View.VISIBLE);
                    backManageViews.add(LAYOUT_CREATE_HOLD);
                }
            });
            LinearLayoutManager linearLayoutManager = new LinearLayoutManager(activity);
            binding.rvOrderList.setLayoutManager(linearLayoutManager);
            binding.rvOrderList.setAdapter(new OrderListAdapter(activity, orderList));
        } else {
            binding.layoutLocation.setVisibility(View.VISIBLE);
            backManageViews.add(LAYOUT_CREATE_HOLD);
        }
    }

    public void checkAuth() {
        String countryCode = countryData.countries.get(binding.spCountry.getSelectedItemPosition()).code;
        String phone = countryCode + binding.editBuyDashPhone.getText().toString().trim();
        if (!TextUtils.isEmpty(phone)) {
            binding.linearProgress.setVisibility(View.VISIBLE);

            WallofCoins.createService(activity).checkAuth(phone, addressStr).enqueue(new Callback<CheckAuthResp>() {
                @Override
                public void onResponse(Call<CheckAuthResp> call, Response<CheckAuthResp> response) {
                    Log.d(TAG, "onResponse: response code==>>" + response.code());
                    binding.linearProgress.setVisibility(View.GONE);
                    if (response.code() == 200) {
                        if (response.body() != null && response.body().getAvailableAuthSources() != null && response.body().getAvailableAuthSources().size() > 0) {
                            if (response.body().getAvailableAuthSources().size() >= 2 && response.body().getAvailableAuthSources().get(1).equals("device")) {
                                hideKeyBoard();
                                password = getDeviceId(getContext());
                                createHold(true);
//                                getAuthTokenCall(false);
                            } else if (response.body().getAvailableAuthSources().get(0).equals("device")) {
                                hideKeyBoard();
                                password = getDeviceId(getContext());
                                createHold(true);
//                                getAuthTokenCall(false);
                                Log.d(TAG, "onResponse: device");
                            } else if (response.body().getAvailableAuthSources().get(0).equals("password")) {
                                binding.layoutCreateHold.setVisibility(View.GONE);
                                binding.linearPhone.setVisibility(View.GONE);
                                binding.linearEmail.setVisibility(View.GONE);
                                binding.linearPassword.setVisibility(View.VISIBLE);
                                backManageViews.add(LAYOUT_PASSWORD);
                                binding.layoutCompletionDetail.setVisibility(View.GONE);
                                binding.rvOrderList.setVisibility(View.GONE);
                                binding.layoutVerifyOtp.setVisibility(View.GONE);
                                binding.rvOffers.setVisibility(View.GONE);
                                binding.textPassAbove.setText("Existing Account Login");
                                binding.etPassword.setHint("Password");
                                Log.d(TAG, "onResponse: password");
                                binding.btnNextPassword.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        hideKeyBoard();
                                        password = binding.etPassword.getText().toString();
                                        Log.d(TAG, "onResponse: pass : " + password);
                                        getAuthTokenCall(true);
                                    }
                                });
                            }
                        }
                    } else if (response.code() == 404) {
                        hideKeyBoard();
                        createHold(false);
                    }
                }

                @Override
                public void onFailure(Call<CheckAuthResp> call, Throwable t) {
                    binding.linearProgress.setVisibility(View.GONE);
                    Toast.makeText(getContext(), R.string.try_again, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Toast.makeText(getContext(), "Enter phone number", Toast.LENGTH_SHORT).show();
        }
    }

    public void hideKeyBoard() {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    public void showKeyBoard() {
        binding.requestCoinsAmountBtcEdittext.requestFocus();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }

    class OrderListAdapter extends RecyclerView.Adapter<OrderListAdapter.VHolder> {

        private final Activity activity;
        private final List<OrderListResp> orderList;

        public OrderListAdapter(Activity activity, List<OrderListResp> orderList) {
            this.activity = activity;
            this.orderList = orderList;
        }

        @Override
        public OrderListAdapter.VHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater layoutInflater = LayoutInflater.from(activity);
            ItemOrderListBinding itemBinding = DataBindingUtil.inflate(layoutInflater, R.layout.item_order_list, parent, false);
            return new VHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(OrderListAdapter.VHolder holder, int position) {
            final OrderListResp orderListResp = orderList.get(position);
            if (orderListResp.account != null && !TextUtils.isEmpty(orderListResp.account)) {
                if (isJSONValid(orderListResp.account)) {
                    ItemBankDetailBinding itemBankBinding = DataBindingUtil.inflate(LayoutInflater.from(activity), R.layout.item_bank_detail, null, false);
                    Glide.with(activity).load(orderListResp.bankLogo).into(itemBankBinding.imageBank);
                    if (orderListResp.bankName != null && !TextUtils.isEmpty(orderListResp.bankName)) {
                        itemBankBinding.textBankName.setText(orderListResp.bankName);
                    } else {
                        itemBankBinding.textBankName.setVisibility(View.GONE);
                    }
                    if (orderListResp.nearestBranch.phone != null && !TextUtils.isEmpty(orderListResp.nearestBranch.phone)) {
                        itemBankBinding.textPhone.setText("Phone: " + orderListResp.nearestBranch.phone);
                    } else {
                        itemBankBinding.textPhone.setVisibility(View.GONE);
                    }

                    if (orderListResp.nameOnAccount != null && !TextUtils.isEmpty(orderListResp.nameOnAccount)) {
                        itemBankBinding.textNameAccount.setText("Name on Account: " + orderListResp.nameOnAccount);
                    } else {
                        itemBankBinding.textNameAccount.setVisibility(View.GONE);
                    }

                    if (orderListResp.payment != null && !TextUtils.isEmpty(orderListResp.payment)) {
                        itemBankBinding.textCashToDeposite.setText("Cash to Deposit: " + orderListResp.payment);
                    } else {
                        itemBankBinding.textCashToDeposite.setVisibility(View.GONE);
                    }

                    if (orderListResp.paymentDue != null && !TextUtils.isEmpty(orderListResp.paymentDue)) {
                        itemBankBinding.textDepositeDue.setText("Deposit Due: " + orderListResp.paymentDue.substring(0, 16).replace("T", " "));
                    } else {
                        itemBankBinding.textDepositeDue.setVisibility(View.GONE);
                    }
                    Type listType = new TypeToken<ArrayList<AccountJson>>() {
                    }.getType();
                    ArrayList<AccountJson> accountList = new Gson().fromJson(orderListResp.account, listType);
                    for (int i = 0; i < accountList.size(); i++) {
                        TextView textView = new TextView(getActivity());
                        textView.setTextSize(16);
                        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                        layoutParams.topMargin = 8;
                        textView.setLayoutParams(layoutParams);
                        textView.setText(accountList.get(i).getLabel() + ": " + accountList.get(i).getValue());
                        itemBankBinding.linearAccountDetail.addView(textView);
                    }

                    holder.itemBinding.layoutCompletionDetail.removeAllViews();
                    holder.itemBinding.layoutCompletionDetail.addView(itemBankBinding.getRoot());

                    if (orderListResp.status.equals("WD")) {
                        itemBankBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                        itemBankBinding.btnDepositFinished.setVisibility(View.VISIBLE);
                    } else {
                        itemBankBinding.btnCancelOrder.setVisibility(View.GONE);
                        itemBankBinding.btnDepositFinished.setVisibility(View.GONE);
                    }

                    itemBankBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            hideKeyBoard();
                            CaptureHoldResp response = new CaptureHoldResp();
                            response.id = orderListResp.id;
                            response.total = orderListResp.total;
                            response.payment = orderListResp.payment;
                            response.paymentDue = orderListResp.paymentDue;
                            response.bankName = orderListResp.bankName;
                            response.nameOnAccount = orderListResp.nameOnAccount;
                            response.account = orderListResp.account;
                            response.status = orderListResp.status;
                            CaptureHoldResp.NearestBranchBean nearestBranchBean = new CaptureHoldResp.NearestBranchBean();
                            nearestBranchBean.name = orderListResp.nearestBranch.name;
                            nearestBranchBean.city = orderListResp.nearestBranch.city;
                            nearestBranchBean.state = orderListResp.nearestBranch.state;
                            nearestBranchBean.phone = orderListResp.nearestBranch.phone;
                            nearestBranchBean.address = orderListResp.nearestBranch.address;
                            response.nearestBranch = nearestBranchBean;
                            response.bankUrl = orderListResp.account;
                            response.bankLogo = orderListResp.account;
                            response.bankIcon = orderListResp.account;
                            response.bankIconHq = orderListResp.account;
                            response.privateId = orderListResp.account;

                            confirmDeposit(response);
                        }
                    });

                    itemBankBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            hideKeyBoard();
                            // call cancel order
                            cancelOrder("" + orderListResp.id);
                        }
                    });
                } else {
                    holder.itemBinding.setItem(orderListResp);
                }
            } else {
                holder.itemBinding.setItem(orderListResp);
            }

            switch (orderListResp.status) {
                case "WD":
                    holder.itemBinding.orderStatus.setText("Status: Waiting Deposit");
                    break;
                case "WDV":
                    holder.itemBinding.orderStatus.setText("Status: Waiting Deposit Verification");
                    break;
                case "RERR":
                    holder.itemBinding.orderStatus.setText("Status: Issue w/ Receipt, Contacted Buyer");
                    break;
                case "DERR":
                    holder.itemBinding.orderStatus.setText("Status: Issue with Deposit, Needs Follow-up");
                    break;
                case "RSD":
                    holder.itemBinding.orderStatus.setText("Status: Reserved for Seller Deposit");
                    break;
                case "RMIT":
                    holder.itemBinding.orderStatus.setText("Status: Remit Address Missing");
                    break;
                case "UCRV":
                    holder.itemBinding.orderStatus.setText("Status: Under Compliance Review");
                    break;
                case "PAYP":
                    holder.itemBinding.orderStatus.setText("Status: Done - Pending Delivery");
                    break;
                case "SENT":
                    holder.itemBinding.orderStatus.setText("Status: Done - Units Delivered");
                    break;
                case "CANC":
                    holder.itemBinding.orderStatus.setText("Status: Buyer Canceled");
                    break;
                case "ACAN":
                    holder.itemBinding.orderStatus.setText("Status: Staff Canceled");
                    break;
                case "EXP":
                    holder.itemBinding.orderStatus.setText("Status: Deposit Time Expired");
                    break;
                case "ML":
                    holder.itemBinding.orderStatus.setText("Status: Meat Locker");
                    break;
                case "MLR":
                    holder.itemBinding.orderStatus.setText("Status: Meat Locker Returned");
                    break;
            }
            if (orderListResp.status.equals("WD")) {
                holder.itemBinding.btnCancelOrder.setVisibility(View.VISIBLE);
                holder.itemBinding.btnDepositFinished.setVisibility(View.VISIBLE);
            } else {
                holder.itemBinding.btnCancelOrder.setVisibility(View.GONE);
                holder.itemBinding.btnDepositFinished.setVisibility(View.GONE);
            }

            holder.itemBinding.btnDepositFinished.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideKeyBoard();
                    CaptureHoldResp response = new CaptureHoldResp();
                    response.id = orderListResp.id;
                    response.total = orderListResp.total;
                    response.payment = orderListResp.payment;
                    response.paymentDue = orderListResp.paymentDue;
                    response.bankName = orderListResp.bankName;
                    response.nameOnAccount = orderListResp.nameOnAccount;
                    response.account = orderListResp.account;
                    response.status = orderListResp.status;
                    CaptureHoldResp.NearestBranchBean nearestBranchBean = new CaptureHoldResp.NearestBranchBean();
                    nearestBranchBean.name = orderListResp.nearestBranch.name;
                    nearestBranchBean.city = orderListResp.nearestBranch.city;
                    nearestBranchBean.state = orderListResp.nearestBranch.state;
                    nearestBranchBean.phone = orderListResp.nearestBranch.phone;
                    nearestBranchBean.address = orderListResp.nearestBranch.address;
                    response.nearestBranch = nearestBranchBean;
                    response.bankUrl = orderListResp.account;
                    response.bankLogo = orderListResp.account;
                    response.bankIcon = orderListResp.account;
                    response.bankIconHq = orderListResp.account;
                    response.privateId = orderListResp.account;

                    confirmDeposit(response);
                }
            });

            holder.itemBinding.btnCancelOrder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideKeyBoard();
                    // call cancel order
                    cancelOrder("" + orderListResp.id);
                }
            });

        }

        @Override
        public int getItemCount() {
            return orderList.size();
        }

        public class VHolder extends RecyclerView.ViewHolder {
            private ItemOrderListBinding itemBinding;

            public VHolder(ItemOrderListBinding itemBinding) {
                super(itemBinding.getRoot());
                this.itemBinding = itemBinding;
            }
        }
    }

    class AccountJson {

        /**
         * displaySort : 0
         * name : fullName
         * value : Ian Marshall
         * label : Full Name
         */

        private String displaySort;
        private String name;
        private String value;
        private String label;

        public String getDisplaySort() {
            return displaySort;
        }

        public void setDisplaySort(String displaySort) {
            this.displaySort = displaySort;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    public void hideViewManageBack(int viewType) {
        binding.layoutCreateHold.setVisibility(View.GONE);
        binding.layoutCompletionDetail.setVisibility(View.GONE);
        binding.rvOffers.setVisibility(View.GONE);
        binding.rvOrderList.setVisibility(View.GONE);
        binding.linearPhone.setVisibility(View.GONE);
        binding.linearEmail.setVisibility(View.GONE);
        binding.linearPassword.setVisibility(View.GONE);
        binding.layoutVerifyOtp.setVisibility(View.GONE);
        binding.layoutLocation.setVisibility(View.GONE);
        Log.d(TAG, "hideViewManageBack: " + viewType);
        switch (viewType) {
            case LAYOUT_COMPLETION_DETAIL:
                binding.layoutCompletionDetail.setVisibility(View.VISIBLE);
                break;
            case LAYOUT_CREATE_HOLD:
                binding.layoutLocation.setVisibility(View.VISIBLE);
                break;
            case LAYOUT_OFFERS:
                binding.rvOffers.setVisibility(View.VISIBLE);
                break;
            case LAYOUT_ORDER_LIST:
                if (isBuyMoreVisible) {
                    binding.btnBuyMore.setVisibility(View.VISIBLE);
                } else {
                    binding.btnBuyMore.setVisibility(View.GONE);
                }
                binding.rvOrderList.setVisibility(View.VISIBLE);
                break;
            case LAYOUT_VERIFY_OTP:
                binding.layoutVerifyOtp.setVisibility(View.VISIBLE);
                break;
        }
        hideKeyBoard();
    }
}
