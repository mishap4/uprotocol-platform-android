/*
 * Copyright (c) 2024 General Motors GTO LLC
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * SPDX-FileType: SOURCE
 * SPDX-FileCopyrightText: 2023 General Motors GTO LLC
 * SPDX-License-Identifier: Apache-2.0
 */

package org.eclipse.uprotocol.core.udiscovery;

import static org.eclipse.uprotocol.common.util.UStatusUtils.isOk;
import static org.eclipse.uprotocol.common.util.log.Formatter.join;
import static org.eclipse.uprotocol.transport.builder.UPayloadBuilder.packToAny;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.eclipse.uprotocol.ULink;
import org.eclipse.uprotocol.common.util.log.Formatter;
import org.eclipse.uprotocol.common.util.log.Key;
import org.eclipse.uprotocol.core.R;
import org.eclipse.uprotocol.core.udiscovery.db.DiscoveryManager;
import org.eclipse.uprotocol.core.udiscovery.interfaces.NetworkStatusInterface;
import org.eclipse.uprotocol.core.udiscovery.internal.Utils;
import org.eclipse.uprotocol.core.udiscovery.v3.FindNodePropertiesResponse;
import org.eclipse.uprotocol.core.udiscovery.v3.FindNodesResponse;
import org.eclipse.uprotocol.core.udiscovery.v3.LookupUriResponse;
import org.eclipse.uprotocol.rpc.URpcListener;
import org.eclipse.uprotocol.uri.builder.UResourceBuilder;
import org.eclipse.uprotocol.uri.serializer.LongUriSerializer;
import org.eclipse.uprotocol.v1.UAttributes;
import org.eclipse.uprotocol.v1.UCode;
import org.eclipse.uprotocol.v1.UEntity;
import org.eclipse.uprotocol.v1.UMessage;
import org.eclipse.uprotocol.v1.UPayload;
import org.eclipse.uprotocol.v1.UStatus;
import org.eclipse.uprotocol.v1.UUri;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

@SuppressWarnings({"java:S1200", "java:S3008", "java:S1134"})
public class UDiscoveryService extends Service implements NetworkStatusInterface {
    private static final String LOG_TAG = Formatter.tag("core", UDiscoveryService.class.getSimpleName());
   public static final UEntity UDISCOVERY_SERVICE = UEntity.newBuilder().setName("core.udiscovery").setVersionMajor(2).build();
    private static final String DATABASE_NOT_INITIALIZED = "Database not initialized";
    private static final String PROPERTY_SDV_ENABLED = "persist.sys.gm.sdv_enable";
    protected static boolean VERBOSE = Log.isLoggable(LOG_TAG, Log.VERBOSE);
    private final ScheduledExecutorService mExecutor = Executors.newScheduledThreadPool(1);
    private final Map<UUri, BiConsumer<UMessage, CompletableFuture<UPayload>>> mMethodHandlers = new HashMap<>();
    private final URpcListener mRequestEventListener = this::handleRequestEvent;
    private final NwConnectionCallbacks mNetworkCallback = new NwConnectionCallbacks(this);
    private RPCHandler mRpcHandler;
    private LoadUtility mDatabaseLoader;
    private AtomicBoolean mDatabaseInitialized = new AtomicBoolean(false);
    private ULink mEclipseULink;
    private ConnectivityManager mConnectivityManager;
    private CompletableFuture<Boolean> mNetworkAvailableFuture = new CompletableFuture<>();
    private Binder mBinder = new Binder() {
    };
    //private USubscription.Stub mUsubStub;

    private static final String NOTIFICATION_CHANNEL_ID = UDiscoveryService.class.getPackageName();
    private static final CharSequence NOTIFICATION_CHANNEL_NAME = "UDiscoveryService";
    private static final int NOTIFICATION_ID = 1;

    public static final String METHOD_LOOKUP_URI = "LookupUri";
    public static final String METHOD_FIND_NODES = "FindNodes";
    public static final String METHOD_FIND_NODE_PROPERTIES = "FindNodeProperties";
    public static final String METHOD_UPDATE_NODE = "UpdateNode";
    public static final String METHOD_ADD_NODES = "AddNodes";
    public static final String METHOD_DELETE_NODES = "DeleteNodes";
    public static final String METHOD_UPDATE_PROPERTY = "UpdateProperty";
    public static final String METHOD_REGISTER_FOR_NOTIFICATIONS = "RegisterForNotifications";
    public static final String METHOD_UNREGISTER_FOR_NOTIFICATIONS = "UnregisterForNotifications";

    // This constructor is for service initialization
    // without this constructor service won't start.
    @SuppressWarnings("unused")
    public UDiscoveryService() {
    }

    @VisibleForTesting
    UDiscoveryService(Context context, RPCHandler rpcHandler, ULink uLink,
            LoadUtility dbLoader, ConnectivityManager connectivityMgr) {
        mRpcHandler = rpcHandler;
        mEclipseULink = uLink;
        mDatabaseLoader = dbLoader;
        mConnectivityManager = connectivityMgr;
        ulinkInit().join();
    }

    private static void logStatus(@NonNull String message, @NonNull UStatus status) {
        //Log.status(LOG_TAG, "logStatus", status, Key.MESSAGE, message);
    }

    private void registerNetworkCallback() {
        mConnectivityManager.requestNetwork(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build(), mNetworkCallback);
    }

    private void unregisterNetworkCallback() {
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        Log.d(LOG_TAG, join(Key.EVENT, "onBind"));
         return mBinder;
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOG_TAG, join(Key.EVENT, "onCreate - Starting uDiscovery"));

        mEclipseULink = ULink.create(getApplicationContext(), UDISCOVERY_SERVICE, mExecutor, (link, ready) -> {
            if (ready) {
                Log.i(LOG_TAG, join(Key.EVENT, "uLink is connected"));
            } else {
                Log.i(LOG_TAG, join(Key.EVENT, "uLink is disconnected"));
            }
        });
        ObserverManager observerManager = new ObserverManager(this);
        Notifier notifier = new Notifier(observerManager, mEclipseULink);
        DiscoveryManager discoveryManager = new DiscoveryManager(notifier);
        AssetUtility assetUtility = new AssetUtility();
        mDatabaseLoader = new LoadUtility(this, assetUtility, discoveryManager);
        mRpcHandler = new RPCHandler(this, assetUtility, discoveryManager, observerManager);
        mConnectivityManager = this.getSystemService(ConnectivityManager.class);
        registerNetworkCallback();
        ulinkInit();
    }

    private void startForegroundService() {
        Log.d(LOG_TAG, join(Key.EVENT, "startForegroundService"));
        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_NONE);
        final NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
        android.app.Notification.Builder notificationBuilder = new android.app.Notification.Builder(this,
                NOTIFICATION_CHANNEL_ID);
        android.app.Notification notification = notificationBuilder.setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getApplicationContext().getResources().getString(
                        R.string.notification_title))
                .setCategory(android.app.Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private synchronized CompletableFuture<Void> ulinkInit() {
        return (CompletableFuture<Void>) mEclipseULink.connect()
                .thenCompose(status -> {
                    logStatus("uLink connect", status);
                    Log.i(LOG_TAG, join(Key.MESSAGE, "uLink.isConnected()", Key.CONNECTION, mEclipseULink.isConnected()));
                    return isOk(status) ?
                            CompletableFuture.completedFuture(status) :
                            CompletableFuture.failedFuture(new Utils.StatusException(status.getCode(), status.getMessage()));
                }).thenRunAsync(() -> {
                    LoadUtility.initLDSCode code = mDatabaseLoader.initializeLDS();
                    boolean isInitialized = (code != LoadUtility.initLDSCode.FAILURE);
                    mDatabaseInitialized.set(isInitialized);
                    if (mEclipseULink.isConnected()) {
                        registerAllMethods();
                        createNotificationTopic();
                    }
                });
    }

    private CompletableFuture<Void> registerAllMethods() {
        Log.i(LOG_TAG, join(Key.EVENT, "registerAllMethods, uLink Connect", Key.STATUS, mEclipseULink.isConnected()));
        return CompletableFuture.allOf(
                        registerMethod(METHOD_LOOKUP_URI, this::executeLookupUri),
                        registerMethod(METHOD_FIND_NODES, this::executeFindNodes),
                        registerMethod(METHOD_UPDATE_NODE, this::executeUpdateNode),
                        registerMethod(METHOD_FIND_NODE_PROPERTIES, this::executeFindNodesProperty),
                        registerMethod(METHOD_ADD_NODES, this::executeAddNodes),
                        registerMethod(METHOD_DELETE_NODES, this::executeDeleteNodes),
                        registerMethod(METHOD_UPDATE_PROPERTY, this::executeUpdateProperty),
                        registerMethod(METHOD_REGISTER_FOR_NOTIFICATIONS, this::executeRegisterNotification),
                        registerMethod(METHOD_UNREGISTER_FOR_NOTIFICATIONS, this::executeUnregisterNotification))
                .exceptionally(e -> {
                    Log.e(LOG_TAG, join("registerAllMethods", Utils.throwableToStatus(e)));
                    return null;
                });
    }

    private void executeLookupUri(@NonNull UMessage requestEvent, @NonNull CompletableFuture<UPayload> responseFuture) {
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            //Log.d(LOG_TAG, Key.EVENT, "LookupUri", Key.REQUEST, stringify(requestEvent));
            responseFuture.complete(mRpcHandler.processLookupUriFromLDS(requestEvent));
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG, "executeLookupUri", Utils.throwableToStatus(e));
            LookupUriResponse response = LookupUriResponse.newBuilder().setStatus(status).build();
            responseFuture.complete(packToAny(response));
        }
    }

    private void executeFindNodes(@NonNull UMessage requestEvent, @NonNull CompletableFuture<UPayload> responseFuture) {
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            //Log.d(LOG_TAG, Key.EVENT, "FindNodes", Key.REQUEST, stringify(requestEvent));
            responseFuture.complete(mRpcHandler.processFindNodesFromLDS(requestEvent));
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG, "executeFindNodes", Utils.throwableToStatus(e));
            FindNodesResponse response = FindNodesResponse.newBuilder().setStatus(status).build();
            responseFuture.complete(packToAny(response));
        }
    }

    private void executeUpdateNode(@NonNull UMessage requestEvent, @NonNull CompletableFuture<UPayload> future) {
        Log.d(LOG_TAG, join(Key.EVENT, "executeUpdateNode"));
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            //Log.d(LOG_TAG, Key.EVENT, "CE1 received for UpdateNode", Key.REQUEST, stringify(requestEvent));
            future.complete(mRpcHandler.processLDSUpdateNode(requestEvent));
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG, "executeUpdateNode", Utils.throwableToStatus(e));
            future.complete(packToAny(status));
        }
    }

    private void executeFindNodesProperty(@NonNull UMessage requestEvent,
            @NonNull CompletableFuture<UPayload> future) {
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            future.complete(mRpcHandler.processFindNodeProperties(requestEvent));
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG,"executeFindNodesProperty", Utils.throwableToStatus(e));
            FindNodePropertiesResponse response = FindNodePropertiesResponse.newBuilder().setStatus(status).build();
            future.complete(packToAny(response));
        }
    }

    private void executeAddNodes(@NonNull UMessage requestEvent, @NonNull CompletableFuture<UPayload> future) {
        Log.d(LOG_TAG, join(Key.EVENT, "executeAddNodes"));
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            //Log.d(LOG_TAG, Key.EVENT, "CE1 received for AddNodes", Key.REQUEST, stringify(requestEvent));
            future.complete(mRpcHandler.processAddNodesLDS(requestEvent));
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG, "executeAddNodes", Utils.throwableToStatus(e));
            future.complete(packToAny(status));
        }
    }

    private void executeDeleteNodes(@NonNull UMessage requestEvent, @NonNull CompletableFuture<UPayload> future) {
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            //Log.i(LOG_TAG, Key.EVENT, "CE1 received for DeleteNodes", Key.REQUEST, stringify(requestEvent));
            future.complete(mRpcHandler.processDeleteNodes(requestEvent));
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG, "executeDeleteNodes", Utils.throwableToStatus(e));
            future.complete(packToAny(status));
        }
    }

    private void executeUpdateProperty(@NonNull UMessage requestEvent,
            @NonNull CompletableFuture<UPayload> future) {
        Log.d(LOG_TAG, join(Key.EVENT, "executeUpdateProperty"));
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            future.complete(mRpcHandler.processLDSUpdateProperty(requestEvent));
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG, "executeUpdateProperty", Utils.throwableToStatus(e));
            future.complete(packToAny(status));
        }
    }

    private void executeRegisterNotification(@NonNull UMessage requestEvent,
            @NonNull CompletableFuture<UPayload> future) {
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            //Log.i(LOG_TAG, Key.EVENT, "CE1 received for Register Notification", Key.REQUEST, stringify(requestEvent));
            UPayload uPayload = mRpcHandler.processNotificationRegistration(requestEvent, METHOD_REGISTER_FOR_NOTIFICATIONS);
            future.complete(uPayload);
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG, "executeRegisterNotification", Utils.throwableToStatus(e));
            future.complete(packToAny(status));
        }
    }

    private void executeUnregisterNotification(@NonNull UMessage requestEvent,
            @NonNull CompletableFuture<UPayload> future) {
        try {
            Utils.checkArgument(mDatabaseInitialized.get(), UCode.FAILED_PRECONDITION, DATABASE_NOT_INITIALIZED);
            //Log.i(LOG_TAG, Key.EVENT, "CE1 received for Unregister Notification", Key.REQUEST, stringify(requestEvent));
            UPayload uPayload = mRpcHandler.processNotificationRegistration(requestEvent, METHOD_UNREGISTER_FOR_NOTIFICATIONS);
            future.complete(uPayload);
        } catch (Exception e) {
            UStatus status = errorStatus(LOG_TAG, "executeUnregisterNotification", Utils.throwableToStatus(e));
            future.complete(packToAny(status));
        }
    }

    private CompletableFuture<UStatus> registerMethod(@NonNull String methodName,
            @NonNull BiConsumer<UMessage, CompletableFuture<UPayload>> handler) {
        final UUri methodUri = UUri.newBuilder().setEntity(UDISCOVERY_SERVICE).setResource(UResourceBuilder.forRpcRequest(methodName)).build();
        return CompletableFuture.supplyAsync(() -> {
            final UStatus status = mEclipseULink.registerRpcListener(methodUri, mRequestEventListener);
            logStatus("Register listener for '" + methodUri + "'", status);
            if (isOk(status)) {
                mMethodHandlers.put(methodUri, handler);
            } else {
                throw new Utils.StatusException(UCode.INVALID_ARGUMENT, "URI not implemented or invalid argument");
            }
            return status;
        });
    }

    private CompletableFuture<UStatus> unregisterMethod(@NonNull String methodName) {
        final UUri methodUri = UUri.newBuilder().setEntity(UDISCOVERY_SERVICE).setResource(UResourceBuilder.forRpcRequest(methodName)).build();
        return CompletableFuture.supplyAsync(() -> {
            final UStatus status = mEclipseULink.registerRpcListener(methodUri, mRequestEventListener);
            logStatus("Unregister listener for '" + methodUri + "'", status);
            mMethodHandlers.remove(methodUri);
            if (!isOk(status)) {
                throw new Utils.StatusException(status.getCode(), status.getMessage());
            }
            return status;
        });
    }

    @Override
    public void onDestroy() {
        Log.d(LOG_TAG, join(Key.EVENT, "onDestroy"));
        unregisterNetworkCallback();
        CompletableFuture.allOf(
                        unregisterMethod(METHOD_LOOKUP_URI),
                        unregisterMethod(METHOD_FIND_NODES),
                        unregisterMethod(METHOD_UPDATE_NODE),
                        unregisterMethod(METHOD_FIND_NODE_PROPERTIES),
                        unregisterMethod(METHOD_ADD_NODES),
                        unregisterMethod(METHOD_DELETE_NODES),
                        unregisterMethod(METHOD_UPDATE_PROPERTY),
                        unregisterMethod(METHOD_REGISTER_FOR_NOTIFICATIONS),
                        unregisterMethod(METHOD_UNREGISTER_FOR_NOTIFICATIONS))
                .exceptionally(e -> {
                    errorStatus(LOG_TAG,"onDestroy", Utils.throwableToStatus(e));
                    return null;
                })
                .thenCompose(it -> mEclipseULink.disconnect())
                .whenComplete((status, exception) -> logStatus("uLink disconnect", status));
        mRpcHandler.shutdown();
        super.onDestroy();
    }

    private void handleRequestEvent(@NonNull UMessage requestEvent, @NonNull CompletableFuture<UPayload>  future) {
        UAttributes uAttributes = UMessage.getDefaultInstance().getAttributes();
        UUri uUri = uAttributes.getSink();
        if (null != uUri) {
            final UUri methodUri = LongUriSerializer.instance().deserialize(uUri.toString());
            final BiConsumer<UMessage, CompletableFuture<UPayload>> handler = mMethodHandlers.get(methodUri);
            if (handler == null) {
                UStatus sts = UStatus.newBuilder().setCode(UCode.INVALID_ARGUMENT)
                        .setMessage("unregistered method " + methodUri).build();
                future.completeExceptionally(new Utils.StatusException(sts.getCode(), sts.getMessage()));
            } else {
                handler.accept(requestEvent, future);
            }
        }
    }

    @Override
    public void setNetworkStatus(boolean status) {
        synchronized (mNetworkCallback) {
            if (status) {
                mNetworkAvailableFuture.complete(true);
            } else {
                mNetworkAvailableFuture = new CompletableFuture<>();
            }
        }
    }

    private void createNotificationTopic() {
//        Log.d(LOG_TAG, Key.REQUEST, "CreateTopic", Key.URI, quote(TOPIC_NODE_NOTIFICATION));
//
//        mUsubStub.createTopic(CreateTopicRequest.newBuilder()
//                        .setTopic(Topic.newBuilder().setUri(TOPIC_NODE_NOTIFICATION).build()).build())
//                .exceptionally(StatusUtils::throwableToStatus)
//                .thenAccept(status -> {
//                    Log.status(LOG_TAG, "createNotificationTopic", status);
//                });
    }

        public static @NonNull UStatus errorStatus(@NonNull String tag, @NonNull String method, @NonNull UStatus status,
            Object... args) {
        android.util.Log.e(tag, join(method, status, args));
        return status;
    }
}
