package com.android.server.wifi;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.IApInterface;
import android.net.wifi.IClientInterface;
import android.net.wifi.IInterfaceEventCallback;
import android.net.wifi.IRttManager;
import android.net.wifi.IWificond;
import android.net.wifi.RttManager;
import android.net.wifi.RttManager.ResponderConfig;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IState;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public final class RttService extends SystemService {

    public static final boolean DBG = true;
    private static final String WIFICOND_SERVICE_NAME = "wificond";

    static class RttServiceImpl extends IRttManager.Stub {

        @Override
        public Messenger getMessenger() {
            return new Messenger(mClientHandler);
        }

        private class ClientHandler extends Handler {

            ClientHandler(android.os.Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {

                if (DBG) {
                    Log.d(TAG, "ClientHandler got" + msg + " what = " + getDescription(msg.what));
                }

                switch (msg.what) {

                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                            Slog.e(TAG, "Send failed, client connection lost");
                        } else {
                            if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                        }
                        if (DBG) Slog.d(TAG, "closing client " + msg.replyTo);
                        synchronized (mLock) {
                            ClientInfo ci = mClients.remove(msg.replyTo);
                            if (ci != null) ci.cleanup();
                        }
                        return;
                    case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                        AsyncChannel ac = new AsyncChannel();
                        ac.connected(mContext, this, msg.replyTo);
                        ClientInfo client = new ClientInfo(ac, msg.sendingUid);
                        synchronized (mLock) {
                            mClients.put(msg.replyTo, client);
                        }
                        ac.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL);
                        return;
                }

                ClientInfo ci;
                synchronized (mLock) {
                    ci = mClients.get(msg.replyTo);
                }
                if (ci == null) {
                    Slog.e(TAG, "Could not find client info for message " + msg.replyTo);
                    replyFailed(msg, RttManager.REASON_INVALID_LISTENER, "Could not find listener");
                    return;
                }
                if (!enforcePermissionCheck(msg)) {
                    replyFailed(msg, RttManager.REASON_PERMISSION_DENIED,
                            "Client doesn't have LOCATION_HARDWARE permission");
                    return;
                }
                final int validCommands[] = {
                        RttManager.CMD_OP_START_RANGING,
                        RttManager.CMD_OP_STOP_RANGING,
                        RttManager.CMD_OP_ENABLE_RESPONDER,
                        RttManager.CMD_OP_DISABLE_RESPONDER,
                        };

                for (int cmd : validCommands) {
                    if (cmd == msg.what) {
                        mStateMachine.sendMessage(Message.obtain(msg));
                        return;
                    }
                }

                replyFailed(msg, RttManager.REASON_INVALID_REQUEST, "Invalid request");
            }

            private String getDescription(int what) {
                switch(what) {
                    case RttManager.CMD_OP_ENABLE_RESPONDER:
                        return "CMD_OP_ENABLE_RESPONDER";
                    case RttManager.CMD_OP_DISABLE_RESPONDER:
                        return "CMD_OP_DISABLE_RESPONDER";
                    default:
                        return "CMD_UNKNOWN";
                }
            }
        }

        private final WifiNative mWifiNative;
        private final Context mContext;
        private final Looper mLooper;
        private RttStateMachine mStateMachine;
        private ClientHandler mClientHandler;
        private WifiInjector mWifiInjector;

        RttServiceImpl(Context context, Looper looper, WifiInjector wifiInjector) {
            mContext = context;
            mWifiNative = wifiInjector.getWifiNative();
            mLooper = looper;
            mWifiInjector = wifiInjector;
        }

        public void startService() {
            mClientHandler = new ClientHandler(mLooper);
            mStateMachine = new RttStateMachine(mLooper);
            mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            int state = intent.getIntExtra(
                                    WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
                            if (DBG) Log.d(TAG, "SCAN_AVAILABLE : " + state);
                            if (state == WifiManager.WIFI_STATE_ENABLED) {
                                mStateMachine.sendMessage(CMD_DRIVER_LOADED);
                            } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                                mStateMachine.sendMessage(CMD_DRIVER_UNLOADED);
                            }
                        }
                    }, new IntentFilter(WifiManager.WIFI_SCAN_AVAILABLE));

            mStateMachine.start();
        }

        private class RttRequest {
            Integer key;
            ClientInfo ci;
            RttManager.RttParams[] params;

            @Override
            public String toString() {
                String str = getClass().getName() + "@" + Integer.toHexString(hashCode());
                if(this.key != null) {
                    return str + " key: " + this.key;
                } else {
                    return str + " key: " + " , null";
                }
            }
        }

        private class ClientInfo {
            private final AsyncChannel mChannel;
            private final int mUid;

            ArrayMap<Integer, RttRequest> mRequests = new ArrayMap<>();
            // Client keys of all outstanding responders.
            Set<Integer> mResponderRequests = new HashSet<>();

            ClientInfo(AsyncChannel channel, int uid) {
                mChannel = channel;
                mUid = uid;
            }

            void addResponderRequest(int key) {
                mResponderRequests.add(key);
            }

            void removeResponderRequest(int key) {
                mResponderRequests.remove(key);
            }

            boolean addRttRequest(int key, RttManager.ParcelableRttParams parcelableParams) {
                if (parcelableParams == null) {
                    return false;
                }

                RttManager.RttParams params[] = parcelableParams.mParams;

                RttRequest request = new RttRequest();
                request.key = key;
                request.ci = this;
                request.params = params;
                mRequests.put(key, request);
                mRequestQueue.add(request);
                return true;
            }

            void removeRttRequest(int key) {
                mRequests.remove(key);
            }

            void reportResponderEnableSucceed(int key, ResponderConfig config) {
                mChannel.sendMessage(RttManager.CMD_OP_ENALBE_RESPONDER_SUCCEEDED, 0, key, config);
            }

            void reportResponderEnableFailed(int key, int reason) {
                mChannel.sendMessage(RttManager.CMD_OP_ENALBE_RESPONDER_FAILED, reason, key);
                removeResponderRequest(key);
            }

            void reportResult(RttRequest request, RttManager.RttResult[] results) {
                RttManager.ParcelableRttResults parcelableResults =
                        new RttManager.ParcelableRttResults(results);

                mChannel.sendMessage(RttManager.CMD_OP_SUCCEEDED,
                        0, request.key, parcelableResults);
                removeRttRequest(request.key);
            }

            void reportFailed(RttRequest request, int reason, String description) {
                reportFailed(request.key, reason, description);
            }

            void reportFailed(int key, int reason, String description) {
                Bundle bundle = new Bundle();
                bundle.putString(RttManager.DESCRIPTION_KEY, description);
                mChannel.sendMessage(RttManager.CMD_OP_FAILED, key, reason, bundle);
                removeRttRequest(key);
            }

            void reportAborted(int key) {
                mChannel.sendMessage(RttManager.CMD_OP_ABORTED, 0, key);
                //All Queued RTT request will be cleaned
                cleanup();
            }

            void cleanup() {
                mRequests.clear();
                mRequestQueue.clear();
                // When client is lost, clean up responder requests and send disable responder
                // message to RttStateMachine.
                mResponderRequests.clear();
                mStateMachine.sendMessage(RttManager.CMD_OP_DISABLE_RESPONDER);
            }

            @Override
            public String toString() {
                return "ClientInfo [uid=" + mUid + ", channel=" + mChannel + "]";
            }
        }

        private Queue<RttRequest> mRequestQueue = new LinkedList<>();

        @GuardedBy("mLock")
        private ArrayMap<Messenger, ClientInfo> mClients = new ArrayMap<>();
        // Lock for mClients.
        private final Object mLock = new Object();

        private static final int BASE = Protocol.BASE_WIFI_RTT_SERVICE;

        private static final int CMD_DRIVER_LOADED                       = BASE + 0;
        private static final int CMD_DRIVER_UNLOADED                     = BASE + 1;
        private static final int CMD_ISSUE_NEXT_REQUEST                  = BASE + 2;
        private static final int CMD_RTT_RESPONSE                        = BASE + 3;
        private static final int CMD_CLIENT_INTERFACE_READY              = BASE + 4;
        private static final int CMD_CLIENT_INTERFACE_DOWN               = BASE + 5;

        // Maximum duration for responder role.
        private static final int MAX_RESPONDER_DURATION_SECONDS = 60 * 10;

        private static class InterfaceEventHandler extends IInterfaceEventCallback.Stub {
            InterfaceEventHandler(RttStateMachine rttStateMachine) {
                mRttStateMachine = rttStateMachine;
            }
            @Override
            public void OnClientTorndownEvent(IClientInterface networkInterface) {
                mRttStateMachine.sendMessage(CMD_CLIENT_INTERFACE_DOWN, networkInterface);
            }
            @Override
            public void OnClientInterfaceReady(IClientInterface networkInterface) {
                mRttStateMachine.sendMessage(CMD_CLIENT_INTERFACE_READY, networkInterface);
            }
            @Override
            public void OnApTorndownEvent(IApInterface networkInterface) { }
            @Override
            public void OnApInterfaceReady(IApInterface networkInterface) { }
            @Override
	    public void OnSoftApClientEvent(byte[] mac_address, boolean connect_status) {}

            private RttStateMachine mRttStateMachine;
        }

        class RttStateMachine extends StateMachine {
            private IWificond mWificond;
            private InterfaceEventHandler mInterfaceEventHandler;
            private IClientInterface mClientInterface;

            DefaultState mDefaultState = new DefaultState();
            EnabledState mEnabledState = new EnabledState();
            InitiatorEnabledState mInitiatorEnabledState = new InitiatorEnabledState();
            ResponderEnabledState mResponderEnabledState = new ResponderEnabledState();
            ResponderConfig mResponderConfig;

            RttStateMachine(Looper looper) {
                super("RttStateMachine", looper);

                // CHECKSTYLE:OFF IndentationCheck
                addState(mDefaultState);
                addState(mEnabledState);
                    addState(mInitiatorEnabledState, mEnabledState);
                    addState(mResponderEnabledState, mEnabledState);
                // CHECKSTYLE:ON IndentationCheck

                setInitialState(mDefaultState);
            }

            class DefaultState extends State {
                @Override
                public boolean processMessage(Message msg) {
                    if (DBG) Log.d(TAG, "DefaultState got" + msg);
                    switch (msg.what) {
                        case CMD_DRIVER_LOADED:
                            transitionTo(mEnabledState);
                            break;
                        case CMD_ISSUE_NEXT_REQUEST:
                            deferMessage(msg);
                            break;
                        case RttManager.CMD_OP_START_RANGING:
                            replyFailed(msg, RttManager.REASON_NOT_AVAILABLE, "Try later");
                            break;
                        case RttManager.CMD_OP_STOP_RANGING:
                            return HANDLED;
                        case RttManager.CMD_OP_ENABLE_RESPONDER:

                            ClientInfo client;
                            synchronized (mLock) {
                                client = mClients.get(msg.replyTo);
                            }
                            if (client == null) {
                                Log.e(TAG, "client not connected yet!");
                                break;
                            }
                            int key = msg.arg2;
                            client.reportResponderEnableFailed(key,
                                    RttManager.REASON_NOT_AVAILABLE);
                            break;
                        case RttManager.CMD_OP_DISABLE_RESPONDER:
                            return HANDLED;
                        default:
                            return NOT_HANDLED;
                    }
                    return HANDLED;
                }
            }

            class EnabledState extends State {
                @Override
                public void enter() {
                    // This allows us to tolerate wificond restarts.
                    // When wificond restarts WifiStateMachine is supposed to go
                    // back to initial state and restart.
                    // 1) RttService watches for WIFI_STATE_ENABLED broadcasts
                    // 2) WifiStateMachine sends these broadcasts in the SupplicantStarted state
                    // 3) Since WSM will only be in SupplicantStarted for as long as wificond is
                    // alive, we refresh our wificond handler here and we don't subscribe to
                    // wificond's death explicitly.
                    mWificond = mWifiInjector.makeWificond();
                    if (mWificond == null) {
                        Log.w(TAG, "Failed to get wificond binder handler");
                        transitionTo(mDefaultState);
                    }
                    mInterfaceEventHandler = new InterfaceEventHandler(mStateMachine);
                    try {
                        mWificond.RegisterCallback(mInterfaceEventHandler);
                        // Get the current client interface, assuming there is at most
                        // one client interface for now.
                        List<IBinder> interfaces = mWificond.GetClientInterfaces();
                        if (interfaces.size() > 0) {
                            mStateMachine.sendMessage(
                                    CMD_CLIENT_INTERFACE_READY,
                                    IClientInterface.Stub.asInterface(interfaces.get(0)));
                        }
                    } catch (RemoteException e1) { }

                }
                @Override
                public void exit() {
                    try {
                        mWificond.UnregisterCallback(mInterfaceEventHandler);
                    } catch (RemoteException e1) { }
                    mInterfaceEventHandler = null;
                }
                @Override
                public boolean processMessage(Message msg) {
                    if (DBG) Log.d(TAG, "EnabledState got" + msg);
                    ClientInfo ci;
                    synchronized (mLock) {
                        ci = mClients.get(msg.replyTo);
                    }

                    switch (msg.what) {
                        case CMD_DRIVER_UNLOADED:
                            transitionTo(mDefaultState);
                            break;
                        case CMD_ISSUE_NEXT_REQUEST:
                            deferMessage(msg);
                            transitionTo(mInitiatorEnabledState);
                            break;
                        case RttManager.CMD_OP_START_RANGING: {
                            RttManager.ParcelableRttParams params =
                                    (RttManager.ParcelableRttParams)msg.obj;
                            if (params == null || params.mParams == null
                                    || params.mParams.length == 0) {
                                replyFailed(msg,
                                        RttManager.REASON_INVALID_REQUEST, "No params");
                            } else if (ci.addRttRequest(msg.arg2, params) == false) {
                                replyFailed(msg,
                                        RttManager.REASON_INVALID_REQUEST, "Unspecified");
                            } else {
                                sendMessage(CMD_ISSUE_NEXT_REQUEST);
                            }
                        }
                            break;
                        case RttManager.CMD_OP_STOP_RANGING:
                            for (Iterator<RttRequest> it = mRequestQueue.iterator();
                                    it.hasNext(); ) {
                                RttRequest request = it.next();
                                if (request.key == msg.arg2) {
                                    if (DBG) Log.d(TAG, "Cancelling not-yet-scheduled RTT");
                                    mRequestQueue.remove(request);
                                    request.ci.reportAborted(request.key);
                                    break;
                                }
                            }
                            break;
                        case RttManager.CMD_OP_ENABLE_RESPONDER:
                            int key = msg.arg2;
                            mResponderConfig =
                                    mWifiNative.enableRttResponder(MAX_RESPONDER_DURATION_SECONDS);
                            if (DBG) Log.d(TAG, "mWifiNative.enableRttResponder called");

                            if (mResponderConfig != null) {
                                // TODO: remove once mac address is added when enabling responder.
                                mResponderConfig.macAddress = mWifiNative.getMacAddress();
                                ci.addResponderRequest(key);
                                ci.reportResponderEnableSucceed(key, mResponderConfig);
                                transitionTo(mResponderEnabledState);
                            } else {
                                Log.e(TAG, "enable responder failed");
                                ci.reportResponderEnableFailed(key, RttManager.REASON_UNSPECIFIED);
                            }
                            break;
                        case RttManager.CMD_OP_DISABLE_RESPONDER:
                            break;
                        case CMD_CLIENT_INTERFACE_DOWN:
                            if (mClientInterface == (IClientInterface) msg.obj) {
                                mClientInterface = null;
                            }
                            break;
                        case CMD_CLIENT_INTERFACE_READY:
                            mClientInterface = (IClientInterface) msg.obj;
                            break;
                        default:
                            return NOT_HANDLED;
                    }
                    return HANDLED;
                }
            }

            class InitiatorEnabledState extends State {
                RttRequest mOutstandingRequest;
                @Override
                public boolean processMessage(Message msg) {
                    if (DBG) Log.d(TAG, "RequestPendingState got" + msg);
                    switch (msg.what) {
                        case CMD_DRIVER_UNLOADED:
                            if (mOutstandingRequest != null) {
                                mWifiNative.cancelRtt(mOutstandingRequest.params);
                                if (DBG) Log.d(TAG, "abort key: " + mOutstandingRequest.key);
                                mOutstandingRequest.ci.reportAborted(mOutstandingRequest.key);
                                mOutstandingRequest = null;
                            }
                            transitionTo(mDefaultState);
                            break;
                        case CMD_ISSUE_NEXT_REQUEST:
                            if (mOutstandingRequest == null) {
                                mOutstandingRequest = issueNextRequest();
                                if (mOutstandingRequest == null) {
                                    transitionTo(mEnabledState);
                                }
                                if(mOutstandingRequest != null) {
                                    if (DBG) Log.d(TAG, "new mOutstandingRequest.key is: " +
                                            mOutstandingRequest.key);
                                } else {
                                    if (DBG) Log.d(TAG,
                                            "CMD_ISSUE_NEXT_REQUEST: mOutstandingRequest =null ");
                                }
                            } else {
                                /* just wait; we'll issue next request after
                                 * current one is finished */
                                 if (DBG) Log.d(TAG, "Current mOutstandingRequest.key is: " +
                                         mOutstandingRequest.key);
                                 if (DBG) Log.d(TAG, "Ignoring CMD_ISSUE_NEXT_REQUEST");
                            }
                            break;
                        case CMD_RTT_RESPONSE:
                            if (DBG) Log.d(TAG, "Received an RTT response from: " + msg.arg2);
                            mOutstandingRequest.ci.reportResult(
                                    mOutstandingRequest, (RttManager.RttResult[])msg.obj);
                            mOutstandingRequest = null;
                            sendMessage(CMD_ISSUE_NEXT_REQUEST);
                            break;
                        case RttManager.CMD_OP_STOP_RANGING:
                            if (mOutstandingRequest != null
                                    && msg.arg2 == mOutstandingRequest.key) {
                                if (DBG) Log.d(TAG, "Cancelling ongoing RTT of: " + msg.arg2);
                                mWifiNative.cancelRtt(mOutstandingRequest.params);
                                mOutstandingRequest.ci.reportAborted(mOutstandingRequest.key);
                                mOutstandingRequest = null;
                                sendMessage(CMD_ISSUE_NEXT_REQUEST);
                            } else {
                                /* Let EnabledState handle this */
                                return NOT_HANDLED;
                            }
                            break;
                        default:
                            return NOT_HANDLED;
                    }
                    return HANDLED;
                }
            }

            // Check if there are still outstanding responder requests from any client.
            private boolean hasOutstandingReponderRequests() {
                synchronized (mLock) {
                    for (ClientInfo client : mClients.values()) {
                        if (!client.mResponderRequests.isEmpty()) {
                            return true;
                        }
                    }
                }
                return false;
            }

            /**
             * Representing an outstanding RTT responder state.
             */
            class ResponderEnabledState extends State {
                @Override
                public boolean processMessage(Message msg) {
                    if (DBG) Log.d(TAG, "ResponderEnabledState got " + msg);
                    ClientInfo ci;
                    synchronized (mLock) {
                        ci = mClients.get(msg.replyTo);
                    }
                    int key = msg.arg2;
                    switch(msg.what) {
                        case RttManager.CMD_OP_ENABLE_RESPONDER:
                            // Responder already enabled, simply return the responder config.
                            ci.addResponderRequest(key);
                            ci.reportResponderEnableSucceed(key, mResponderConfig);
                            return HANDLED;
                        case RttManager.CMD_OP_DISABLE_RESPONDER:
                            if (ci != null) {
                                ci.removeResponderRequest(key);
                            }
                            // Only disable responder when there are no outstanding clients.
                            if (!hasOutstandingReponderRequests()) {
                                if (!mWifiNative.disableRttResponder()) {
                                    Log.e(TAG, "disable responder failed");
                                }
                                if (DBG) Log.d(TAG, "mWifiNative.disableRttResponder called");
                                transitionTo(mEnabledState);
                            }
                            return HANDLED;
                        case RttManager.CMD_OP_START_RANGING:
                        case RttManager.CMD_OP_STOP_RANGING:  // fall through
                            // Concurrent initiator and responder role is not supported.
                            replyFailed(msg,
                                    RttManager.REASON_INITIATOR_NOT_ALLOWED_WHEN_RESPONDER_ON,
                                    "Initiator not allowed when responder is turned on");
                            return HANDLED;
                        default:
                            return NOT_HANDLED;
                    }
                }
            }

            /**
             * Returns name of current state.
             */
            String currentState() {
                IState state = getCurrentState();
                return state == null ? "null" : state.getName();
            }
        }

        void replySucceeded(Message msg, Object obj) {
            if (msg.replyTo != null) {
                Message reply = Message.obtain();
                reply.what = RttManager.CMD_OP_SUCCEEDED;
                reply.arg2 = msg.arg2;
                reply.obj = obj;
                try {
                    msg.replyTo.send(reply);
                } catch (RemoteException e) {
                    // There's not much we can do if reply can't be sent!
                }
            } else {
                // locally generated message; doesn't need a reply!
            }
        }

        void replyFailed(Message msg, int reason, String description) {
            Message reply = Message.obtain();
            reply.what = RttManager.CMD_OP_FAILED;
            reply.arg1 = reason;
            reply.arg2 = msg.arg2;

            Bundle bundle = new Bundle();
            bundle.putString(RttManager.DESCRIPTION_KEY, description);
            reply.obj = bundle;

            try {
                if (msg.replyTo != null) {
                    msg.replyTo.send(reply);
                }
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        }

        boolean enforcePermissionCheck(Message msg) {
            try {
                mContext.enforcePermission(Manifest.permission.LOCATION_HARDWARE,
                         -1, msg.sendingUid, "LocationRTT");
            } catch (SecurityException e) {
                Log.e(TAG, "UID: " + msg.sendingUid + " has no LOCATION_HARDWARE Permission");
                return false;
            }
            return true;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump RttService from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " without permission "
                        + android.Manifest.permission.DUMP);
                return;
            }
            pw.println("current state: " + mStateMachine.currentState());
            pw.println("clients:");
            synchronized (mLock) {
                for (ClientInfo client : mClients.values()) {
                    pw.println("  " + client);
                }
            }
        }

        private WifiNative.RttEventHandler mEventHandler = new WifiNative.RttEventHandler() {
            @Override
            public void onRttResults(RttManager.RttResult[] result) {
                mStateMachine.sendMessage(CMD_RTT_RESPONSE, result);
            }
        };

        RttRequest issueNextRequest() {
            RttRequest request = null;
            while (mRequestQueue.isEmpty() == false) {
                request = mRequestQueue.remove();
                if(request !=  null) {
                    if (mWifiNative.requestRtt(request.params, mEventHandler)) {
                        if (DBG) Log.d(TAG, "Issued next RTT request with key: " + request.key);
                        return request;
                    } else {
                        Log.e(TAG, "Fail to issue key at native layer");
                        request.ci.reportFailed(request,
                                RttManager.REASON_UNSPECIFIED, "Failed to start");
                    }
                }
            }

            /* all requests exhausted */
            if (DBG) Log.d(TAG, "No more requests left");
            return null;
        }
        @Override
        public RttManager.RttCapabilities getRttCapabilities() {
            return mWifiNative.getRttCapabilities();
        }
    }

    private static final String TAG = "RttService";
    RttServiceImpl mImpl;
    private final HandlerThread mHandlerThread;

    public RttService(Context context) {
        super(context);
        mHandlerThread = new HandlerThread("WifiRttService");
        mHandlerThread.start();
        Log.i(TAG, "Creating " + Context.WIFI_RTT_SERVICE);
    }

    @Override
    public void onStart() {
        mImpl = new RttServiceImpl(getContext(),
                mHandlerThread.getLooper(), WifiInjector.getInstance());

        Log.i(TAG, "Starting " + Context.WIFI_RTT_SERVICE);
        publishBinderService(Context.WIFI_RTT_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.i(TAG, "Registering " + Context.WIFI_RTT_SERVICE);
            if (mImpl == null) {
                mImpl = new RttServiceImpl(getContext(),
                        mHandlerThread.getLooper(),  WifiInjector.getInstance());
            }
            mImpl.startService();
        }
    }


}
