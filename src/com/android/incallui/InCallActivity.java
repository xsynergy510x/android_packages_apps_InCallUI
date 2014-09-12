/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only.
 *
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.incallui;

import java.lang.reflect.Array;
import java.util.ArrayList;

import com.android.services.telephony.common.Call;
import com.android.services.telephony.common.Call.State;
import com.android.services.telephony.common.CallDetails;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.MSimTelephonyManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Phone app "in call" screen.
 */
public class InCallActivity extends Activity {

    public static final String SHOW_DIALPAD_EXTRA = "InCallActivity.show_dialpad";

    private static final int INVALID_RES_ID = -1;

    protected CallButtonFragment mCallButtonFragment;
    protected CallCardFragment mCallCardFragment;
    private AnswerFragment mAnswerFragment;
    protected DialpadFragment mDialpadFragment;
    protected ConferenceManagerFragment mConferenceManagerFragment;
    private boolean mIsForegroundActivity;
    protected AlertDialog mDialog;
    private AlertDialog mModifyCallPromptDialog;

    /** Use to pass 'showDialpad' from {@link #onNewIntent} to {@link #onResume} */
    private boolean mShowDialpadRequested;
    private boolean mConferenceManagerShown;

    private boolean mUseFullScreenCallerPhoto;

    // This enum maps to Phone.SuppService defined in telephony
    private enum SuppService {
        UNKNOWN, SWITCH, SEPARATE, TRANSFER, CONFERENCE, REJECT, HANGUP;
    }

    private ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings();
        }
    };

    private int[] mCoverWindowCoords = null;
    private BroadcastReceiver mLidStateChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WindowManagerPolicy.ACTION_LID_STATE_CHANGED.equals(intent.getAction())) {
                boolean on = intent.getIntExtra(WindowManagerPolicy.EXTRA_LID_STATE,
                        WindowManagerPolicy.WindowManagerFuncs.LID_ABSENT)
                        == WindowManagerPolicy.WindowManagerFuncs.LID_CLOSED;
                showSmartCover(on);
            }
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        Log.d(this, "onCreate()...  this = " + this);

        super.onCreate(icicle);

        if (MSimTelephonyManager.getDefault().getMultiSimConfiguration()
                == MSimTelephonyManager.MultiSimVariants.DSDA) {
            return;
        }

        mCoverWindowCoords = getResources().getIntArray(
                com.android.internal.R.array.config_smartCoverWindowCoords);
        if (mCoverWindowCoords != null && mCoverWindowCoords.length != 4) {
            // make sure there are exactly 4 dimensions provided, or ignore
            mCoverWindowCoords = null;
        }

        // set this flag so this activity will stay in front of the keyguard
        // Have the WindowManager filter out touch events that are "too fat".
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;

        getWindow().addFlags(flags);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // TODO(klp): Do we need to add this back when prox sensor is not available?
        // lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;

        // Inflate everything in incall_screen.xml and add it to the screen.
        setContentView(R.layout.incall_screen);

        initializeInCall();

        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.INCOMING_CALL_STYLE),
                false, mSettingsObserver);
        updateSettings();

        Log.d(this, "onCreate(): exit");
    }

    @Override
    protected void onStart() {
        Log.d(this, "onStart()...");
        super.onStart();

        if (MSimTelephonyManager.getDefault().getMultiSimConfiguration()
                == MSimTelephonyManager.MultiSimVariants.DSDA) {
            return;
        }

        // setting activity should be last thing in setup process
        InCallPresenter.getInstance().setActivity(this);
    }

    @Override
    protected void onResume() {
        Log.i(this, "onResume()...");
        super.onResume();

        mIsForegroundActivity = true;
        InCallPresenter.getInstance().onUiShowing(true);

        if (mShowDialpadRequested) {
            mCallButtonFragment.displayDialpad(true);
            mShowDialpadRequested = false;
        }
        updateSystemBarTranslucency();
        if (mCoverWindowCoords != null) {
            registerReceiver(mLidStateChangeReceiver, new IntentFilter(
                    WindowManagerPolicy.ACTION_LID_STATE_CHANGED));
        }
    }

    // onPause is guaranteed to be called when the InCallActivity goes
    // in the background.
    @Override
    protected void onPause() {
        Log.d(this, "onPause()...");
        super.onPause();
        if (mCoverWindowCoords != null) {
            unregisterReceiver(mLidStateChangeReceiver);
        }

        mIsForegroundActivity = false;

        mDialpadFragment.onDialerKeyUp(null);

        InCallPresenter.getInstance().onUiShowing(false);
    }

    @Override
    protected void onStop() {
        Log.d(this, "onStop()...");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(this, "onDestroy()...  this = " + this);

        InCallPresenter.getInstance().setActivity(null);

        super.onDestroy();
    }

    /**
     * Returns true when theActivity is in foreground (between onResume and onPause).
     */
    /* package */ boolean isForegroundActivity() {
        return mIsForegroundActivity;
    }

    protected boolean hasPendingErrorDialog() {
        return mDialog != null;
    }
    /**
     * Dismisses the in-call screen.
     *
     * We never *really* finish() the InCallActivity, since we don't want to get destroyed and then
     * have to be re-created from scratch for the next call.  Instead, we just move ourselves to the
     * back of the activity stack.
     *
     * This also means that we'll no longer be reachable via the BACK button (since moveTaskToBack()
     * puts us behind the Home app, but the home app doesn't allow the BACK key to move you any
     * farther down in the history stack.)
     *
     * (Since the Phone app itself is never killed, this basically means that we'll keep a single
     * InCallActivity instance around for the entire uptime of the device.  This noticeably improves
     * the UI responsiveness for incoming calls.)
     */
    @Override
    public void finish() {
        if (MSimTelephonyManager.getDefault().getMultiSimConfiguration()
                == MSimTelephonyManager.MultiSimVariants.DSDA) {
            super.finish();
            return;
        }
        Log.i(this, "finish().  Dialog showing: " + (mDialog != null));

        // skip finish if we are still showing a dialog.
        if (!hasPendingErrorDialog() && !mAnswerFragment.hasPendingDialogs()) {
            super.finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.d(this, "onNewIntent: intent = " + intent);

        // We're being re-launched with a new Intent.  Since it's possible for a
        // single InCallActivity instance to persist indefinitely (even if we
        // finish() ourselves), this sequence can potentially happen any time
        // the InCallActivity needs to be displayed.

        // Stash away the new intent so that we can get it in the future
        // by calling getIntent().  (Otherwise getIntent() will return the
        // original Intent from when we first got created!)
        setIntent(intent);

        // Activities are always paused before receiving a new intent, so
        // we can count on our onResume() method being called next.

        // Just like in onCreate(), handle the intent.
        internalResolveIntent(intent);
    }

    @Override
    public void onBackPressed() {
        Log.d(this, "onBackPressed()...");

        if (mAnswerFragment.isVisible()) {
            // The Back key, just like the Home key, is always disabled
            // while an incoming call is ringing.  (The user *must* either
            // answer or reject the call before leaving the incoming-call
            // screen.)
            Log.d(this, "BACK key while ringing: ignored");

            // And consume this event; *don't* call super.onBackPressed().
            return;
        }

        // BACK is also used to exit out of any "special modes" of the
        // in-call UI:

        if (mDialpadFragment.isVisible()) {
            mCallButtonFragment.displayDialpad(false);  // do the "closing" animation
            return;
        } else if (mConferenceManagerFragment.isVisible()) {
            mConferenceManagerFragment.setVisible(false);
            mConferenceManagerShown = false;
            updateSystemBarTranslucency();
            return;
        }

        // Always disable the Back key while an incoming call is ringing
        final Call call = CallList.getInstance().getIncomingCall();
        if (call != null) {
            Log.d(this, "Consume Back press for an inconing call");
            return;
        }

        // Nothing special to do.  Fall back to the default behavior.
        super.onBackPressed();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // push input to the dialer.
        if ((mDialpadFragment.isVisible()) && (mDialpadFragment.onDialerKeyUp(event))){
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_CALL) {
            // Always consume CALL to be sure the PhoneWindow won't do anything with it
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL:
                boolean handled = InCallPresenter.getInstance().handleCallKey();
                if (!handled) {
                    Log.w(this, "InCallActivity should always handle KEYCODE_CALL in onKeyDown");
                }
                // Always consume CALL to be sure the PhoneWindow won't do anything with it
                return true;

            // Note there's no KeyEvent.KEYCODE_ENDCALL case here.
            // The standard system-wide handling of the ENDCALL key
            // (see PhoneWindowManager's handling of KEYCODE_ENDCALL)
            // already implements exactly what the UI spec wants,
            // namely (1) "hang up" if there's a current active call,
            // or (2) "don't answer" if there's a current ringing call.

            case KeyEvent.KEYCODE_CAMERA:
                // Disable the CAMERA button while in-call since it's too
                // easy to press accidentally.
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                // Ringer silencing handled by PhoneWindowManager.
                break;

            case KeyEvent.KEYCODE_MUTE:
                // toggle mute
                CallCommandClient.getInstance().mute(!AudioModeProvider.getInstance().getMute());
                return true;

            // Various testing/debugging features, enabled ONLY when VERBOSE == true.
            case KeyEvent.KEYCODE_SLASH:
                if (Log.VERBOSE) {
                    Log.v(this, "----------- InCallActivity View dump --------------");
                    // Dump starting from the top-level view of the entire activity:
                    Window w = this.getWindow();
                    View decorView = w.getDecorView();
                    decorView.debug();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_EQUALS:
                // TODO: Dump phone state?
                break;
        }

        if (event.getRepeatCount() == 0 && handleDialerKeyDown(keyCode, event)) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private boolean handleDialerKeyDown(int keyCode, KeyEvent event) {
        Log.v(this, "handleDialerKeyDown: keyCode " + keyCode + ", event " + event + "...");

        // As soon as the user starts typing valid dialable keys on the
        // keyboard (presumably to type DTMF tones) we start passing the
        // key events to the DTMFDialer's onDialerKeyDown.
        if (mDialpadFragment.isVisible()) {
            return mDialpadFragment.onDialerKeyDown(event);

            // TODO: If the dialpad isn't currently visible, maybe
            // consider automatically bringing it up right now?
            // (Just to make sure the user sees the digits widget...)
            // But this probably isn't too critical since it's awkward to
            // use the hard keyboard while in-call in the first place,
            // especially now that the in-call UI is portrait-only...
        }

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        InCallPresenter.getInstance().getProximitySensor().onConfigurationChanged(config);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) { // On touch.
        if (InCallPresenter.getInstance().getProximitySensor().isScreenOffByProximity())
            return true; 

        return super.dispatchTouchEvent(event);
    }

    public CallButtonFragment getCallButtonFragment() {
        return mCallButtonFragment;
    }

    private void internalResolveIntent(Intent intent) {
        final String action = intent.getAction();

        if (action.equals(intent.ACTION_MAIN)) {
            // This action is the normal way to bring up the in-call UI.
            //
            // But we do check here for one extra that can come along with the
            // ACTION_MAIN intent:

            if (intent.hasExtra(SHOW_DIALPAD_EXTRA)) {
                // SHOW_DIALPAD_EXTRA can be used here to specify whether the DTMF
                // dialpad should be initially visible.  If the extra isn't
                // present at all, we just leave the dialpad in its previous state.

                final boolean showDialpad = intent.getBooleanExtra(SHOW_DIALPAD_EXTRA, false);
                Log.d(this, "- internalResolveIntent: SHOW_DIALPAD_EXTRA: " + showDialpad);

                relaunchedFromDialer(showDialpad);
            }

            return;
        }
    }

    private void relaunchedFromDialer(boolean showDialpad) {
        mShowDialpadRequested = showDialpad;

        if (mShowDialpadRequested) {
            // If there's only one line in use, AND it's on hold, then we're sure the user
            // wants to use the dialpad toward the exact line, so un-hold the holding line.
            final Call call = CallList.getInstance().getActiveOrBackgroundCall();
            if (call != null && call.getState() == State.ONHOLD) {
                CallCommandClient.getInstance().hold(call.getCallId(), false);
            }
        }
    }

    protected void initializeInCall() {
        if (mCallButtonFragment == null) {
            mCallButtonFragment = (CallButtonFragment) getFragmentManager()
                    .findFragmentById(R.id.callButtonFragment);
            mCallButtonFragment.setEnabled(false, false);
        }

        if (mCallCardFragment == null) {
            mCallCardFragment = (CallCardFragment) getFragmentManager()
                    .findFragmentById(R.id.callCardFragment);
        }

        if (mAnswerFragment == null) {
            mAnswerFragment = (AnswerFragment) getFragmentManager()
                    .findFragmentById(R.id.answerFragment);
        }

        if (mDialpadFragment == null) {
            mDialpadFragment = (DialpadFragment) getFragmentManager()
                    .findFragmentById(R.id.dialpadFragment);
            getFragmentManager().beginTransaction().hide(mDialpadFragment).commit();
        }

        if (mConferenceManagerFragment == null) {
            mConferenceManagerFragment = (ConferenceManagerFragment) getFragmentManager()
                    .findFragmentById(R.id.conferenceManagerFragment);
            mConferenceManagerFragment.getView().setVisibility(View.INVISIBLE);
        }
    }

    protected void showSmartCover(boolean show) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        final int windowHeight = mCoverWindowCoords[2] - mCoverWindowCoords[0];
        final int windowWidth = metrics.widthPixels - mCoverWindowCoords[1]
                - (metrics.widthPixels - mCoverWindowCoords[3]);

        final int stretch = ViewGroup.LayoutParams.MATCH_PARENT;

        View main = findViewById(R.id.main);
        View callCard = mCallCardFragment.getView();
        if (show) {
            // clear bg color
            main.setBackground(null);

            // center
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(windowWidth, stretch);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            main.setLayoutParams(lp);

            // adjust callcard height
            ViewGroup.LayoutParams params = callCard.getLayoutParams();
            params.height = windowHeight;

            callCard.setSystemUiVisibility(callCard.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

            // disable touches
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            // reset default parameters
            main.setBackgroundColor(R.color.incall_button_background);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(stretch, stretch);
            main.setLayoutParams(lp);

            ViewGroup.LayoutParams params = mCallCardFragment.getView().getLayoutParams();
            params.height = stretch;

            callCard.setSystemUiVisibility(callCard.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_FULLSCREEN & ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
        callCard.invalidate();
        main.requestLayout();
    }

    private void toast(String text) {
        final Toast toast = Toast.makeText(this, text, Toast.LENGTH_SHORT);

        toast.show();
    }

    /**
     * Simulates a user click to hide the dialpad. This will update the UI to show the call card,
     * update the checked state of the dialpad button, and update the proximity sensor state.
     */
    public void hideDialpadForDisconnect() {
        mCallButtonFragment.displayDialpad(false);
    }

    public void dismissKeyguard(boolean dismiss) {
        if (dismiss) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    public void displayDialpad(boolean showDialpad) {
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        if (showDialpad) {
            ft.setCustomAnimations(R.anim.incall_dialpad_slide_in, 0);
            ft.show(mDialpadFragment);
        } else {
            ft.setCustomAnimations(0, R.anim.incall_dialpad_slide_out);
            ft.hide(mDialpadFragment);
        }
        ft.commitAllowingStateLoss();

        InCallPresenter.getInstance().getProximitySensor().onDialpadVisible(showDialpad);
    }

    public boolean isDialpadVisible() {
        return mDialpadFragment.isVisible();
    }

    public void displayManageConferencePanel(boolean showPanel) {
        if (showPanel) {
            mConferenceManagerFragment.setVisible(true);
            mConferenceManagerShown = true;
            updateSystemBarTranslucency();
        }
    }

    public void onManageConferenceDoneClicked() {
        if (mConferenceManagerShown && !mConferenceManagerFragment.isVisible()) {
            mConferenceManagerShown = false;
            updateSystemBarTranslucency();
        }
    }

    public void updateSystemBarTranslucency() {
        int flags = 0;
        final Window window = getWindow();
        final InCallPresenter.InCallState inCallState =
                InCallPresenter.getInstance().getInCallState();

        if (!mConferenceManagerShown) {
            flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
        }
        if (mUseFullScreenCallerPhoto && inCallState == InCallPresenter.InCallState.INCOMING) {
            flags |= WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
        }

        window.setFlags(flags, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS |
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.getDecorView().requestFitSystemWindows();
    }

    // The function is called when Modify Call button gets pressed.
    // The function creates and displays modify call options.
    public void displayModifyCallOptions(final int callId) {
        final ArrayList<CharSequence> items = new ArrayList<CharSequence>();
        final ArrayList<Integer> itemToCallType = new ArrayList<Integer>();
        final Resources res = getResources();

        // Prepare the string array and mapping.
        items.add(res.getText(R.string.modify_call_option_voice));
        itemToCallType.add(CallDetails.CALL_TYPE_VOICE);

        items.add(res.getText(R.string.modify_call_option_vt_rx));
        itemToCallType.add(CallDetails.CALL_TYPE_VT_RX);

        items.add(res.getText(R.string.modify_call_option_vt_tx));
        itemToCallType.add(CallDetails.CALL_TYPE_VT_TX);

        items.add(res.getText(R.string.modify_call_option_vt));
        itemToCallType.add(CallDetails.CALL_TYPE_VT);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.modify_call_option_title);
        final AlertDialog alert;

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int item) {
                Toast.makeText(getApplicationContext(), items.get(item), Toast.LENGTH_SHORT).show();
                final int selCallType = itemToCallType.get(item);
                log("Videocall: ModifyCall: upgrade/downgrade to "
                        + CallUtils.fromCallType(selCallType));
                InCallPresenter.getInstance().sendModifyCallRequest(callId, selCallType);
                dialog.dismiss();
            }
        };
        int currCallType = CallUtils.getCallType(CallList.getInstance().getCall(callId));
        int index = itemToCallType.indexOf(currCallType);
        builder.setSingleChoiceItems(items.toArray(new CharSequence[0]), index, listener);
        alert = builder.create();
        alert.show();
    }

    public void displayModifyCallConsentDialog(Call call) {
        log("VideoCall: displayModifyCallConsentDialog");

        if (mModifyCallPromptDialog != null) {
            log("VideoCall: - DISMISSING mModifyCallPromptDialog.");
            mModifyCallPromptDialog.dismiss(); // safe even if already dismissed
            mModifyCallPromptDialog = null;
        }

        boolean error = CallUtils.hasCallModifyFailed(call);
        int callType = CallUtils.getProposedCallType(call);
        if (!error) {
            String str = getResources().getString(R.string.accept_modify_call_request_prompt);
            if (callType == CallDetails.CALL_TYPE_VT) {
                str = getResources().getString(R.string.upgrade_vt_prompt);
            } else if (callType == CallDetails.CALL_TYPE_VT_TX) {
                str = getResources().getString(R.string.upgrade_vt_tx_prompt);
            } else if (callType == CallDetails.CALL_TYPE_VT_RX) {
                str = getResources().getString(R.string.upgrade_vt_rx_prompt);
            }

            final ModifyCallConsentListener onConsentListener =
                    new ModifyCallConsentListener(call);
            mModifyCallPromptDialog = new AlertDialog.Builder(this)
                    .setMessage(str)
                    .setPositiveButton(R.string.modify_call_prompt_yes,
                            onConsentListener)
                    .setNegativeButton(R.string.modify_call_prompt_no,
                            onConsentListener)
                    .setOnDismissListener(onConsentListener)
                    .create();
            mModifyCallPromptDialog.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

            mModifyCallPromptDialog.show();

        } else {
            log("VideoCall: Modify Call request failed.");
            String errorMsg = getResources().getString(R.string.modify_call_failure_str);
            toast(errorMsg);
            // We are not explicitly dismissing mModifyCallPromptDialog
            // here since it is dismissed at the beginning of this function.
            // Note, connection type change will be rejected by
            // the Modify Call Consent dialog.
        }
    }

    private class ModifyCallConsentListener implements DialogInterface.OnClickListener,
            DialogInterface.OnDismissListener {
        private boolean mClicked = false;
        private Call mCall;

        public ModifyCallConsentListener(Call call) {
            mCall = call;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            log("VideoCall: ConsentDialog: Clicked on button with ID: " + which);
            mClicked = true;
            switch (which) {
                case DialogInterface.BUTTON_POSITIVE:
                    InCallPresenter.getInstance().modifyCallConfirm(true, mCall);
                    break;
                case DialogInterface.BUTTON_NEGATIVE:
                    InCallPresenter.getInstance().modifyCallConfirm(false, mCall);
                    break;
                default:
                    loge("videocall: No handler for this button, ID:" + which);
            }
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            if (!mClicked) {
                log("VideoCall: ConsentDialog: Dismissing the dialog");
                InCallPresenter.getInstance().modifyCallConfirm(false, mCall);
            }
        }
    }

    public void onAvpUpgradeFailure(String errorString) {
        Log.e(this,"VideoCall: onAvpUpgradeFailure: errorString: " + errorString);
        toast(getResources().getString(R.string.modify_call_failure_str));
    }

    public void showPostCharWaitDialog(int callId, String chars) {
        final PostCharDialogFragment fragment = new PostCharDialogFragment(callId,  chars);
        fragment.show(getFragmentManager(), "postCharWait");
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (mCallCardFragment != null) {
            mCallCardFragment.dispatchPopulateAccessibilityEvent(event);
        }
        return super.dispatchPopulateAccessibilityEvent(event);
    }

    public void maybeShowErrorDialogOnDisconnect(Call call) {
        Log.d(this, "maybeShowErrorDialogOnDisconnect: Call=" + call);

        if (!isFinishing() && call != null) {
            final int resId = getResIdForDisconnectCause(call.getDisconnectCause(),
                    call.getSuppServNotification());
            if (resId != INVALID_RES_ID) {
                showErrorDialog(resId);
            }
        }
    }

    public void dismissPendingDialogs() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }

        mAnswerFragment.dismissPendingDialogues();
        if (mModifyCallPromptDialog != null) {
            mModifyCallPromptDialog.dismiss();
            mModifyCallPromptDialog = null;
        }
    }

    /**
     * Handle a failure notification for a supplementary service
     * (i.e. conference, switch, separate, transfer, etc.).
     */
    void onSuppServiceFailed(int service) {
        Log.d(this, "onSuppServiceFailed: " + service);
        SuppService  result = SuppService.values()[service];
        int errorMessageResId;

        switch (result) {
            case SWITCH:
                // Attempt to switch foreground and background/incoming calls failed
                // ("Failed to switch calls")
                errorMessageResId = R.string.incall_error_supp_service_switch;
                break;

            case SEPARATE:
                // Attempt to separate a call from a conference call
                // failed ("Failed to separate out call")
                errorMessageResId = R.string.incall_error_supp_service_separate;
                break;

            case TRANSFER:
                // Attempt to connect foreground and background calls to
                // each other (and hanging up user's line) failed ("Call
                // transfer failed")
                errorMessageResId = R.string.incall_error_supp_service_transfer;
                break;

            case CONFERENCE:
                // Attempt to add a call to conference call failed
                // ("Conference call failed")
                errorMessageResId = R.string.incall_error_supp_service_conference;
                break;

            case REJECT:
                // Attempt to reject an incoming call failed
                // ("Call rejection failed")
                errorMessageResId = R.string.incall_error_supp_service_reject;
                break;

            case HANGUP:
                // Attempt to release a call failed ("Failed to release call(s)")
                errorMessageResId = R.string.incall_error_supp_service_hangup;
                break;

            case UNKNOWN:
            default:
                // Attempt to use a service we don't recognize or support
                // ("Unsupported service" or "Selected service failed")
                errorMessageResId = R.string.incall_error_supp_service_unknown;
                break;
        }
        showErrorDialog(errorMessageResId);
    }

    /**
     * Utility function to bring up a generic "error" dialog.
     */
    private void showErrorDialog(int resId) {
        final CharSequence msg = getResources().getText(resId);
        Log.i(this, "Show Dialog: " + msg);

        dismissPendingDialogs();

        mDialog = new AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    onDialogDismissed();
                }})
            .setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    onDialogDismissed();
                }})
            .create();

        mDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mDialog.show();
    }

    private int getResIdForDisconnectCause(Call.DisconnectCause cause,
            Call.SsNotification notification) {
        int resId = INVALID_RES_ID;

        if (cause == Call.DisconnectCause.INCOMING_MISSED) {
            // If the network sends SVC Notification then this dialog will be displayed
            // in case of B when the incoming call at B is not answered and gets forwarded
            // to C
            if (notification != null && notification.notificationType == 1 &&
                    notification.code ==
                    Call.SsNotification.MT_CODE_ADDITIONAL_CALL_FORWARDED) {
                resId = R.string.callUnanswered_forwarded;
            }
        } else if (cause == Call.DisconnectCause.CALL_BARRED) {
            // When call is disconnected with this code then it can either be barring from
            // MO side or MT side.
            // In MT case, if network sends SVC Notification then this dialog will be
            // displayed when A is calling B & incoming is barred on B.
            if (notification != null && notification.notificationType == 0 &&
                    notification.code == Call.SsNotification.MO_CODE_INCOMING_CALLS_BARRED) {
                resId = R.string.callFailed_incoming_cb_enabled;
            } else {
                resId = R.string.callFailed_cb_enabled;
            }
        } else if (cause == Call.DisconnectCause.FDN_BLOCKED) {
            resId = R.string.callFailed_fdn_only;
        } else if (cause == Call.DisconnectCause.CS_RESTRICTED) {
            resId = R.string.callFailed_dsac_restricted;
        } else if (cause == Call.DisconnectCause.CS_RESTRICTED_EMERGENCY) {
            resId = R.string.callFailed_dsac_restricted_emergency;
        } else if (cause == Call.DisconnectCause.CS_RESTRICTED_NORMAL) {
            resId = R.string.callFailed_dsac_restricted_normal;
        } else if (cause == Call.DisconnectCause.DIAL_MODIFIED_TO_USSD) {
            resId = R.string.callFailed_dialToUssd;
        } else if (cause == Call.DisconnectCause.DIAL_MODIFIED_TO_SS) {
            resId = R.string.callFailed_dialToSs;
        } else if (cause == Call.DisconnectCause.DIAL_MODIFIED_TO_DIAL) {
            resId = R.string.callFailed_dialToDial;
        }

        return resId;
    }

    private void onDialogDismissed() {
        mDialog = null;
        InCallPresenter.getInstance().onDismissDialog();
    }

    private void updateSettings() {
        int incomingCallStyle = Settings.System.getInt(getContentResolver(),
                Settings.System.INCOMING_CALL_STYLE,
                Settings.System.INCOMING_CALL_STYLE_FULLSCREEN_PHOTO);
        mUseFullScreenCallerPhoto =
                incomingCallStyle == Settings.System.INCOMING_CALL_STYLE_FULLSCREEN_PHOTO;
        mCallButtonFragment.setHideMode(mUseFullScreenCallerPhoto ? View.GONE : View.INVISIBLE);
        mCallButtonFragment.getPresenter().setShowButtonsIfIdle(!mUseFullScreenCallerPhoto);
        updateSystemBarTranslucency();
    }

    private void log(String msg) {
        Log.d(this, msg);
    }

    private void loge(String msg) {
        Log.e(this, msg);
    }

    public void updateDsdaTab() {
        Log.e(this, "updateDsdaTab : Not supported ");
    }
}
