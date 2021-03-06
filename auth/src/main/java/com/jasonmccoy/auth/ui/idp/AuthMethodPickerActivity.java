/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jasonmccoy.auth.ui.idp;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.GoogleAuthProvider;
import com.jasonmccoy.auth.AuthUI;
import com.jasonmccoy.auth.IdpResponse;
import com.jasonmccoy.auth.R;
import com.jasonmccoy.auth.ResultCodes;
import com.jasonmccoy.auth.provider.AuthCredentialHelper;
import com.jasonmccoy.auth.provider.FacebookProvider;
import com.jasonmccoy.auth.provider.GoogleProvider;
import com.jasonmccoy.auth.provider.IdpProvider;
import com.jasonmccoy.auth.ui.AppCompatBase;
import com.jasonmccoy.auth.ui.BaseHelper;
import com.jasonmccoy.auth.ui.FlowParameters;
import com.jasonmccoy.auth.ui.TaskFailureLogger;
import com.jasonmccoy.auth.ui.email.RegisterEmailActivity;
import com.jasonmccoy.auth.util.signincontainer.SaveSmartLock;

import java.util.ArrayList;
import java.util.List;

/**
 * Presents the list of authentication options for this app to the user. If an
 * identity provider option is selected, a {@link CredentialSignInHandler}
 * is launched to manage the IDP-specific sign-in flow. If email authentication is chosen,
 * the {@link RegisterEmailActivity} is started.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class AuthMethodPickerActivity extends AppCompatBase
        implements IdpProvider.IdpCallback, View.OnClickListener {
    private static final String TAG = "AuthMethodPicker";
    private static final int RC_EMAIL_FLOW = 2;
    private static final int RC_ACCOUNT_LINK = 3;

    private ArrayList<IdpProvider> mIdpProviders;
    @Nullable
    private SaveSmartLock mSaveSmartLock;

    public static Intent createIntent(Context context, FlowParameters flowParams) {
        return BaseHelper.createBaseIntent(context, AuthMethodPickerActivity.class, flowParams);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.auth_method_picker_layout);
        mSaveSmartLock = mActivityHelper.getSaveSmartLockInstance();
        findViewById(R.id.email_provider).setOnClickListener(this);

        populateIdpList(mActivityHelper.getFlowParams().providerInfo);

        int logoId = mActivityHelper.getFlowParams().logoId;
        ImageView logo = (ImageView) findViewById(R.id.logo);
        if (logoId == AuthUI.NO_LOGO) {
            logo.setVisibility(View.GONE);
        } else {
            logo.setImageResource(logoId);
        }
    }

    private void populateIdpList(List<AuthUI.IdpConfig> providers) {
        mIdpProviders = new ArrayList<>();
        for (AuthUI.IdpConfig idpConfig : providers) {
            switch (idpConfig.getProviderId()) {
                case AuthUI.GOOGLE_PROVIDER:
                    mIdpProviders.add(new GoogleProvider(this, idpConfig));
                    break;
                case AuthUI.FACEBOOK_PROVIDER:
                    mIdpProviders.add(new FacebookProvider(
                            this, idpConfig, mActivityHelper.getFlowParams().themeId));
                    break;
                case AuthUI.EMAIL_PROVIDER:
                    findViewById(R.id.email_provider).setVisibility(View.VISIBLE);
                    break;
                default:
                    Log.e(TAG, "Encountered unknown IDPProvider parcel with type: "
                            + idpConfig.getProviderId());
            }
        }

        ViewGroup btnHolder = (ViewGroup) findViewById(R.id.btn_holder);
        for (final IdpProvider provider : mIdpProviders) {
            View loginButton = null;
            switch (provider.getProviderId()) {
                case GoogleAuthProvider.PROVIDER_ID:
                    loginButton = getLayoutInflater()
                            .inflate(R.layout.idp_button_google, btnHolder, false);
                    break;
                case FacebookAuthProvider.PROVIDER_ID:
                    loginButton = getLayoutInflater()
                            .inflate(R.layout.idp_button_facebook, btnHolder, false);
                    break;
                default:
                    Log.e(TAG, "No button for provider " + provider.getProviderId());
            }

            if (loginButton != null) {
                loginButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        mActivityHelper.showLoadingDialog(R.string.progress_dialog_loading);
                        provider.startLogin(AuthMethodPickerActivity.this);
                    }
                });
                provider.setAuthenticationCallback(this);
                btnHolder.addView(loginButton, 0);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_EMAIL_FLOW) {
            if (resultCode == ResultCodes.OK) {
                finish(ResultCodes.OK, data);
            }
        } else if (requestCode == RC_ACCOUNT_LINK) {
            finish(resultCode, data);
        } else {
            for (IdpProvider provider : mIdpProviders) {
                provider.onActivityResult(requestCode, resultCode, data);
            }
        }
    }

    @Override
    public void onSuccess(final IdpResponse response) {
        AuthCredential credential = AuthCredentialHelper.getAuthCredential(response);
        mActivityHelper.getFirebaseAuth()
                .signInWithCredential(credential)
                .addOnFailureListener(
                        new TaskFailureLogger(TAG, "Firebase sign in with credential unsuccessful"))
                .addOnCompleteListener(new CredentialSignInHandler(
                        this,
                        mActivityHelper,
                        mSaveSmartLock,
                        RC_ACCOUNT_LINK,
                        response));
    }

    @Override
    public void onFailure(Bundle extra) {
        // stay on this screen
        mActivityHelper.dismissDialog();
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.email_provider) {
            startActivityForResult(
                    RegisterEmailActivity.createIntent(this, mActivityHelper.getFlowParams()),
                    RC_EMAIL_FLOW);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIdpProviders != null) {
            for (final IdpProvider provider : mIdpProviders) {
                if (provider instanceof GoogleProvider) {
                    ((GoogleProvider) provider).disconnect();
                }
            }
        }
    }
}
