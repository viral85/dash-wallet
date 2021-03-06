/*
 * Copyright 2014-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;

import com.google.gson.Gson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.wallofcoins.response.CreateHoldResp;

/**
 * @author Andreas Schildbach
 */
public class BuyDashPref {

    private final SharedPreferences prefs;
    private final Gson gson;


    private static final String CREATE_HOLD_RESP = "create_hold_resp";
    private static final String AUTH_TOKEN = "auth_token";
    private static final String HOLD_ID = "hold_id";
    private static final String PHONE = "phone";
    private static final String EMAIL = "email";


    private static final Logger log = LoggerFactory.getLogger(BuyDashPref.class);

    public BuyDashPref(final SharedPreferences prefs) {
        this.prefs = prefs;
        gson = new Gson();
    }

    public String getAuthToken() {
        return prefs.getString(AUTH_TOKEN, "");
    }

    public void setAuthToken(String authToken) {
        prefs.edit().putString(AUTH_TOKEN, authToken).apply();
    }

    public String getPhone() {
        return prefs.getString(PHONE, "");
    }

    public void setPhone(String authToken) {
        prefs.edit().putString(PHONE, authToken).apply();
    }

    public String getEmail() {
        return prefs.getString(EMAIL, "");
    }

    public void setEmail(String authToken) {
        prefs.edit().putString(EMAIL, authToken).apply();
    }

    public String getHoldId() {
        return prefs.getString(HOLD_ID, "");
    }

    public void setHoldId(String holdId) {
        prefs.edit().putString(HOLD_ID, holdId).apply();
    }

    public CreateHoldResp getCreateHoldResp() {
        return gson.fromJson(prefs.getString(CREATE_HOLD_RESP, ""), CreateHoldResp.class);
    }

    public void setCreateHoldResp(CreateHoldResp createHoldResp) {
        prefs.edit().putString(CREATE_HOLD_RESP, gson.toJson(createHoldResp)).apply();
    }

    public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
