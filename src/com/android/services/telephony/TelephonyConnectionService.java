/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.services.telephony;

import android.net.Uri;
import android.telephony.DisconnectCause;
import android.telephony.ServiceState;
import android.text.TextUtils;

import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection.PostDialListener;
import com.android.internal.telephony.Phone;

import android.telecomm.Connection;
import android.telecomm.ConnectionRequest;
import android.telecomm.ConnectionService;
import android.telecomm.Response;
import android.telecomm.Subscription;

import java.util.HashSet;
import java.util.Set;

/**
 * The parent class for telephony-based call services. Subclasses provide the specific phone (GSM,
 * CDMA, etc...) to use.
 */
public abstract class TelephonyConnectionService extends ConnectionService {
    private static final Set<com.android.internal.telephony.Connection> sKnownConnections
            = new HashSet<>();

    /** {@inheritDoc} */
    @Override
    public void onFindSubscriptions(
            Uri handle,
            Response<Uri, Subscription> response) {
        try {
            respondWithResult(handle, response, canCall(handle) ? new Subscription() : null);
        } catch (Exception e) {
            respondWithError(
                    handle,
                    response,
                    DisconnectCause.ERROR_UNSPECIFIED,  // Internal error
                    "onFindSubscriptions error: " + e.toString());
        }
    }

    /**
     * Initiates the underlying Telephony call, then creates a {@link TelephonyConnection}
     * by calling
     * {@link #createTelephonyConnection(ConnectionRequest,
     *         com.android.internal.telephony.Connection)}
     * at the appropriate time. Should be called by the subclass.
     */
    protected void startCallWithPhone(
            Phone phone,
            ConnectionRequest request,
            Response<ConnectionRequest, Connection> response) {
        Log.d(this, "startCallWithPhone: %s.", request);

        if (phone == null) {
            respondWithError(
                    request,
                    response,
                    DisconnectCause.ERROR_UNSPECIFIED,  // Generic internal error
                    "Phone is null");
            return;
        }

        if (request.getHandle() == null) {
            respondWithError(
                    request,
                    response,
                    DisconnectCause.NO_PHONE_NUMBER_SUPPLIED,
                    "Handle is null");
            return;
        }

        String number = request.getHandle().getSchemeSpecificPart();
        if (TextUtils.isEmpty(number)) {
            respondWithError(
                    request,
                    response,
                    DisconnectCause.INVALID_NUMBER,
                    "Unable to parse number");
            return;
        }

        if (!checkServiceStateForOutgoingCall(phone, request, response)) {
            return;
        }

        com.android.internal.telephony.Connection connection;
        try {
            connection = phone.dial(number);
        } catch (CallStateException e) {
            Log.e(this, e, "Call to Phone.dial failed with exception");
            respondWithError(
                    request,
                    response,
                    DisconnectCause.ERROR_UNSPECIFIED,  // Generic internal error
                    e.getMessage());
            return;
        }

        if (connection == null) {
            respondWithError(
                    request,
                    response,
                    DisconnectCause.ERROR_UNSPECIFIED,  // Generic internal error
                    "Call to phone.dial failed");
            return;
        }

        try {
            final TelephonyConnection telephonyConnection =
                    createTelephonyConnection(request, connection);
            respondWithResult(request, response, telephonyConnection);

            final com.android.internal.telephony.Connection connectionCopy = connection;
            PostDialListener postDialListener = new PostDialListener() {
                @Override
                public void onPostDialWait() {
                    TelephonyConnectionService.this.onPostDialWait(telephonyConnection,
                            connectionCopy.getRemainingPostDialString());
                }
            };
            connection.addPostDialListener(postDialListener);
        } catch (Exception e) {
            Log.e(this, e, "Call to createConnection failed with exception");
            respondWithError(
                    request,
                    response,
                    DisconnectCause.ERROR_UNSPECIFIED,  // Generic internal error
                    e.getMessage());
        }
    }

    private boolean checkServiceStateForOutgoingCall(
            Phone phone,
            ConnectionRequest request,
            Response<ConnectionRequest, Connection> response) {
        int state = phone.getServiceState().getState();
        switch (state) {
            case ServiceState.STATE_IN_SERVICE:
                return true;
            case ServiceState.STATE_OUT_OF_SERVICE:
                respondWithError(
                        request,
                        response,
                        DisconnectCause.OUT_OF_SERVICE,
                        null);
                break;
            case ServiceState.STATE_EMERGENCY_ONLY:
                respondWithError(
                        request,
                        response,
                        DisconnectCause.EMERGENCY_ONLY,
                        null);
                break;
            case ServiceState.STATE_POWER_OFF:
                respondWithError(
                        request,
                        response,
                        DisconnectCause.POWER_OFF,
                        null);
                break;
            default:
                // Internal error, but we pass it upwards and do not crash.
                Log.d(this, "Unrecognized service state %d", state);
                respondWithError(
                        request,
                        response,
                        DisconnectCause.ERROR_UNSPECIFIED,
                        "Unrecognized service state " + state);
                break;
        }
        return false;
    }

    protected <REQUEST, RESULT> void respondWithError(
            REQUEST request,
            Response<REQUEST, RESULT> response,
            int errorCode,
            String errorMsg) {
        Log.d(this, "respondWithError %s: %d %s", request, errorCode, errorMsg);
        response.onError(request, errorCode, errorMsg);
    }

    protected void respondWithResult(
            Uri request,
            Response<Uri, Subscription> response,
            Subscription result) {
        Log.d(this, "respondWithResult %s -> %s", request, result);
        response.onResult(request, result);
    }

    protected void respondWithResult(
            ConnectionRequest request,
            Response<ConnectionRequest, Connection> response,
            Connection result) {
        Log.d(this, "respondWithResult %s -> %s", request, result);
        response.onResult(request, result);
    }

    protected final TelephonyConnection createTelephonyConnection(
            ConnectionRequest request,
            final com.android.internal.telephony.Connection connection) {
        final TelephonyConnection telephonyConnection =
                onCreateTelephonyConnection(request, connection);
        sKnownConnections.add(connection);
        telephonyConnection.addConnectionListener(new Connection.ListenerBase() {
            @Override
            public void onDestroyed(Connection c) {
                telephonyConnection.removeConnectionListener(this);
                sKnownConnections.remove(connection);
            }
        });

        return telephonyConnection;
    }

    protected static boolean isConnectionKnown(
            com.android.internal.telephony.Connection connection) {
        return sKnownConnections.contains(connection);
    }

    /**
     * Determine whether this {@link TelephonyConnectionService} can place a call
     * to the supplied handle (phone number).
     *
     * @param handle The proposed handle.
     * @return {@code true} if the handle can be called.
     */
    protected abstract boolean canCall(Uri handle);

    /**
     * Create a Telephony-specific {@link Connection} object.
     *
     * @param request A request for creating a {@link Connection}.
     * @param connection An underlying Telephony {@link com.android.internal.telephony.Connection}
     *         to use.
     * @return A new {@link TelephonyConnection}.
     */
    protected abstract TelephonyConnection onCreateTelephonyConnection(
            ConnectionRequest request,
            com.android.internal.telephony.Connection connection);
}