package org.mariotaku.twidere.activity;

import static android.text.TextUtils.isEmpty;
import static org.mariotaku.twidere.util.Utils.createPickImageIntent;
import static org.mariotaku.twidere.util.Utils.createTakePhotoIntent;
import static org.mariotaku.twidere.util.Utils.isMyAccount;
import static org.mariotaku.twidere.util.Utils.parseString;
import static org.mariotaku.twidere.util.Utils.showErrorToast;

import java.io.File;

import org.mariotaku.popupmenu.PopupMenu;
import org.mariotaku.popupmenu.PopupMenu.OnMenuItemClickListener;
import org.mariotaku.twidere.R;
import org.mariotaku.twidere.app.TwidereApplication;
import org.mariotaku.twidere.loader.ParcelableUserLoader;
import org.mariotaku.twidere.loader.UserBannerImageLoader;
import org.mariotaku.twidere.model.ParcelableUser;
import org.mariotaku.twidere.model.SingleResponse;
import org.mariotaku.twidere.util.AsyncTask;
import org.mariotaku.twidere.util.AsyncTask.Status;
import org.mariotaku.twidere.util.AsyncTaskManager;
import org.mariotaku.twidere.util.AsyncTwitterWrapper.UpdateProfileBannerImageTask;
import org.mariotaku.twidere.util.AsyncTwitterWrapper.UpdateProfileImageTask;
import org.mariotaku.twidere.util.AsyncTwitterWrapper.UpdateProfileTask;
import org.mariotaku.twidere.util.EnvironmentAccessor;
import org.mariotaku.twidere.util.LazyImageLoader;
import org.mariotaku.twidere.util.TwitterWrapper;
import org.mariotaku.twidere.view.ProfileNameBannerContainer;
import org.mariotaku.twidere.view.iface.IExtendedView.OnSizeChangedListener;

import twitter4j.User;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.LoaderManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.Loader;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

public class EditUserProfileActivity extends BaseDialogWhenLargeActivity implements OnSizeChangedListener, TextWatcher,
		OnClickListener {

	private static final int LOADER_ID_USER = 1;
	private static final int LOADER_ID_BANNER = 2;

	private static final int REQUEST_UPLOAD_PROFILE_IMAGE = 1;
	private static final int REQUEST_UPLOAD_PROFILE_BANNER_IMAGE = 2;

	private LazyImageLoader mProfileImageLoader;

	private ProfileNameBannerContainer mProfileNameBannerContainer;
	private ImageView mProfileImageView;
	private EditText mEditName, mEditDescription, mEditLocation, mEditUrl;
	private View mProgress, mContent;
	private View mProfileImageContainer, mProfileBannerContainer;
	private AsyncTaskManager mAsyncTaskManager;

	private boolean mBackPressed;
	private long mAccountId;
	private int mBannerWidth;
	private ParcelableUser mUser;

	private boolean mUserInfoLoaderInitialized, mBannerImageLoaderInitialized;

	private PopupMenu mPopupMenu;

	private final Handler mHandler = new Handler();

	private AsyncTask<Void, Void, ?> mTask;

	private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (mUser == null) return;
			final String action = intent.getAction();
			if (BROADCAST_PROFILE_UPDATED.equals(action)) {
				if (mUser == null || intent.getLongExtra(INTENT_KEY_USER_ID, -1) == mUser.user_id) {
					getUserInfo();
				}
			}
		}
	};

	private final LoaderCallbacks<Bitmap> mBannerImageCallback = new LoaderCallbacks<Bitmap>() {

		@Override
		public Loader<Bitmap> onCreateLoader(final int id, final Bundle args) {
			if (mUser == null || mUser.profile_banner_url == null
					|| !mUser.profile_banner_url.equals(mProfileNameBannerContainer.getTag())) {
				mProfileNameBannerContainer.setBanner(null);
			}
			final int def_width = getResources().getDisplayMetrics().widthPixels;
			final int width = mBannerWidth > 0 ? mBannerWidth : def_width;
			return new UserBannerImageLoader(EditUserProfileActivity.this, mUser, width, true);
		}

		@Override
		public void onLoaderReset(final Loader<Bitmap> loader) {
		}

		@Override
		public void onLoadFinished(final Loader<Bitmap> loader, final Bitmap data) {
			mProfileNameBannerContainer.setBanner(data);
			mProfileNameBannerContainer.setTag(data != null ? mUser.profile_banner_url : null);
		}

	};

	private final LoaderCallbacks<SingleResponse<ParcelableUser>> mUserInfoLoaderCallbacks = new LoaderCallbacks<SingleResponse<ParcelableUser>>() {

		@Override
		public Loader<SingleResponse<ParcelableUser>> onCreateLoader(final int id, final Bundle args) {
			mProgress.setVisibility(View.VISIBLE);
			mContent.setVisibility(View.GONE);
			setSupportProgressBarIndeterminateVisibility(true);
			return new ParcelableUserLoader(EditUserProfileActivity.this, mAccountId, mAccountId, null, getIntent()
					.getExtras(), false, false);
		}

		@Override
		public void onLoaderReset(final Loader<SingleResponse<ParcelableUser>> loader) {

		}

		@Override
		public void onLoadFinished(final Loader<SingleResponse<ParcelableUser>> loader,
				final SingleResponse<ParcelableUser> data) {
			if (data.data != null && data.data.user_id > 0) {
				displayUser(data.data);
				getBannerImage();
			} else {
				finish();
			}
			setSupportProgressBarIndeterminateVisibility(false);
		}

	};

	private final OnMenuItemClickListener mProfileBannerImageMenuListener = new OnMenuItemClickListener() {

		@Override
		public boolean onMenuItemClick(final MenuItem item) {
			if (mUser == null) return false;
			switch (item.getItemId()) {
				case MENU_TAKE_PHOTO: {
					final Uri uri = createTempFileUri();
					final Intent intent = createTakePhotoIntent(uri, null, null, 2, 1, true);
					startActivityForResult(intent, REQUEST_UPLOAD_PROFILE_BANNER_IMAGE);
					mTask = new UpdateProfileBannerImageTaskInternal(EditUserProfileActivity.this, mAsyncTaskManager,
							mAccountId, uri, true);
					break;
				}
				case MENU_PICK_FROM_GALLERY: {
					final Uri uri = createTempFileUri();
					final Intent intent = createPickImageIntent(uri, null, null, 2, 1, true);
					try {
						startActivityForResult(intent, REQUEST_UPLOAD_PROFILE_BANNER_IMAGE);
						mTask = new UpdateProfileBannerImageTaskInternal(EditUserProfileActivity.this,
								mAsyncTaskManager, mAccountId, uri, true);
					} catch (final Exception e) {
						Log.w(LOGTAG, e);
					}
					break;
				}
				case MENU_DELETE: {
					mTask = new RemoveProfileBannerTaskInternal(mUser.account_id);
					mTask.execute();
					break;
				}
			}
			return true;
		}

	};

	private final OnMenuItemClickListener mProfileImageMenuListener = new OnMenuItemClickListener() {

		@Override
		public boolean onMenuItemClick(final MenuItem item) {
			if (mUser == null) return false;
			switch (item.getItemId()) {
				case MENU_TAKE_PHOTO: {
					final Uri uri = createTempFileUri();
					final Intent intent = createTakePhotoIntent(uri, null, null, 1, 1, true);
					startActivityForResult(intent, REQUEST_UPLOAD_PROFILE_IMAGE);
					mTask = new UpdateProfileImageTaskInternal(EditUserProfileActivity.this, mAsyncTaskManager,
							mAccountId, uri, true);
					break;
				}
				case MENU_PICK_FROM_GALLERY: {
					final Uri uri = createTempFileUri();
					final Intent intent = createPickImageIntent(uri, null, null, 1, 1, true);
					try {
						startActivityForResult(intent, REQUEST_UPLOAD_PROFILE_IMAGE);
						mTask = new UpdateProfileImageTaskInternal(EditUserProfileActivity.this, mAsyncTaskManager,
								mAccountId, uri, true);
					} catch (final Exception e) {
						Log.w(LOGTAG, e);
					}
					break;
				}
			}
			return true;
		}

	};

	private final Runnable mBackPressTimeoutRunnable = new Runnable() {

		@Override
		public void run() {
			mBackPressed = false;
		}

	};

	@Override
	public void afterTextChanged(final Editable s) {
	}

	@Override
	public void beforeTextChanged(final CharSequence s, final int length, final int start, final int end) {
	}

	@Override
	public void onBackPressed() {
		if (!backPressed()) return;
		super.onBackPressed();
	}

	@Override
	public void onClick(final View view) {
		if (mUser == null) return;
		if (mPopupMenu != null) {
			mPopupMenu.dismiss();
		}
		switch (view.getId()) {
			case R.id.profile_image_container: {
				mPopupMenu = PopupMenu.getInstance(this, view);
				mPopupMenu.inflate(R.menu.action_profile_image);
				mPopupMenu.setOnMenuItemClickListener(mProfileImageMenuListener);
				break;
			}
			case R.id.profile_banner_container: {
				mPopupMenu = PopupMenu.getInstance(this, view);
				mPopupMenu.inflate(R.menu.action_profile_banner_image);
				final Menu menu = mPopupMenu.getMenu();
				final MenuItem delete_submenu = menu.findItem(MENU_DELETE_SUBMENU);
				delete_submenu.setVisible(!isEmpty(mUser.profile_banner_url));
				mPopupMenu.setOnMenuItemClickListener(mProfileBannerImageMenuListener);
				break;
			}
			default: {
				return;
			}
		}
		mPopupMenu.show();

	}

	@Override
	public void onContentChanged() {
		super.onContentChanged();
		mProgress = findViewById(R.id.progress);
		mContent = findViewById(R.id.content);
		mProfileNameBannerContainer = (ProfileNameBannerContainer) findViewById(R.id.profile_name_banner_container);
		mProfileImageContainer = findViewById(R.id.profile_image_container);
		mProfileBannerContainer = findViewById(R.id.profile_banner_container);
		mProfileImageView = (ImageView) findViewById(R.id.profile_image);
		mEditName = (EditText) findViewById(R.id.name);
		mEditDescription = (EditText) findViewById(R.id.description);
		mEditLocation = (EditText) findViewById(R.id.location);
		mEditUrl = (EditText) findViewById(R.id.url);
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		requestSupportWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		super.onCreate(savedInstanceState);
		final Bundle extras = getIntent().getExtras();
		if (extras == null || !isMyAccount(this, extras.getLong(INTENT_KEY_ACCOUNT_ID))) {
			finish();
			return;
		}
		mAsyncTaskManager = TwidereApplication.getInstance(this).getAsyncTaskManager();
		mProfileImageLoader = TwidereApplication.getInstance(this).getProfileImageLoader();
		mAccountId = extras.getLong(INTENT_KEY_ACCOUNT_ID);
		setContentView(R.layout.edit_user_profile);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		mProfileNameBannerContainer.setOnSizeChangedListener(this);
		mEditName.addTextChangedListener(this);
		mEditDescription.addTextChangedListener(this);
		mEditLocation.addTextChangedListener(this);
		mEditUrl.addTextChangedListener(this);
		mProfileImageContainer.setOnClickListener(this);
		mProfileBannerContainer.setOnClickListener(this);
		getUserInfo();
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.menu_edit_user_profile, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
			case MENU_HOME: {
				onBackPressed();
				break;
			}
			case MENU_SAVE: {
				final String name = parseString(mEditName.getText());
				final String url = parseString(mEditUrl.getText());
				final String location = parseString(mEditLocation.getText());
				final String description = parseString(mEditDescription.getText());
				mTask = new UpdateProfileTaskInternal(this, mAsyncTaskManager, mAccountId, name, url, location,
						description);
				mTask.execute();
				return true;
			}
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public boolean onPrepareOptionsMenu(final Menu menu) {
		final MenuItem save = menu.findItem(MENU_SAVE);
		if (save != null) {
			save.setEnabled(mHasUnsavedChanges() && (mTask == null || mTask.getStatus() != AsyncTask.Status.RUNNING));
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public void onSizeChanged(final View view, final int w, final int h, final int oldw, final int oldh) {
	}

	@Override
	public void onStart() {
		super.onStart();
		final IntentFilter filter = new IntentFilter(BROADCAST_PROFILE_UPDATED);
		registerReceiver(mStatusReceiver, filter);
	}

	@Override
	public void onStop() {
		unregisterReceiver(mStatusReceiver);
		super.onStop();
	}

	@Override
	public void onTextChanged(final CharSequence s, final int length, final int start, final int end) {
		invalidateSupportOptionsMenu();
	}

	@Override
	protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
		if (resultCode == RESULT_CANCELED) return;
		if (mTask == null || mTask.getStatus() != Status.PENDING) return;
		switch (requestCode) {
			case REQUEST_UPLOAD_PROFILE_BANNER_IMAGE: {
				mTask.execute();
				break;
			}
			case REQUEST_UPLOAD_PROFILE_IMAGE: {
				mTask.execute();
				break;
			}
		}

	}

	private boolean backPressed() {
		if (!mHasUnsavedChanges()) return true;
		mHandler.removeCallbacks(mBackPressTimeoutRunnable);
		if (!mBackPressed) {
			Toast.makeText(this, R.string.unsaved_change_back_pressed, Toast.LENGTH_SHORT).show();
			mBackPressed = true;
			mHandler.postDelayed(mBackPressTimeoutRunnable, 2000);
			return false;
		}
		mBackPressed = false;
		return true;
	}

	private Uri createTempFileUri() {
		final File cache_dir = EnvironmentAccessor.getExternalCacheDir(this);
		final File file = new File(cache_dir, "tmp_image_" + System.currentTimeMillis());
		return Uri.fromFile(file);
	}

	private void displayUser(final ParcelableUser user) {
		mUser = user;
		if (user != null && user.user_id > 0) {
			mProgress.setVisibility(View.GONE);
			mContent.setVisibility(View.VISIBLE);
			mEditName.setText(user.name);
			mEditDescription.setText(user.description);
			mEditLocation.setText(user.location);
			mEditUrl.setText(user.url);
			mProfileImageLoader.displayImage(mProfileImageView, user.profile_image_url);
			getBannerImage();
		} else {
			mProgress.setVisibility(View.GONE);
			mContent.setVisibility(View.GONE);
		}
	}

	private void getBannerImage() {
		final LoaderManager lm = getSupportLoaderManager();
		lm.destroyLoader(LOADER_ID_BANNER);
		if (mBannerImageLoaderInitialized) {
			lm.restartLoader(LOADER_ID_BANNER, null, mBannerImageCallback);
		} else {
			lm.initLoader(LOADER_ID_BANNER, null, mBannerImageCallback);
			mBannerImageLoaderInitialized = true;
		}
	}

	private void getUserInfo() {
		final LoaderManager lm = getSupportLoaderManager();
		lm.destroyLoader(LOADER_ID_USER);
		if (mUserInfoLoaderInitialized) {
			lm.restartLoader(LOADER_ID_USER, null, mUserInfoLoaderCallbacks);
		} else {
			lm.initLoader(LOADER_ID_USER, null, mUserInfoLoaderCallbacks);
			mUserInfoLoaderInitialized = true;
		}
	}

	private void setUpdateState(final boolean start) {
		setSupportProgressBarIndeterminateVisibility(start);
		mEditName.setEnabled(!start);
		mEditDescription.setEnabled(!start);
		mEditLocation.setEnabled(!start);
		mEditUrl.setEnabled(!start);
		mProfileImageContainer.setEnabled(!start);
		mProfileImageContainer.setOnClickListener(start ? null : this);
		mProfileBannerContainer.setEnabled(!start);
		mProfileBannerContainer.setOnClickListener(start ? null : this);
		invalidateSupportOptionsMenu();
	}

	boolean mHasUnsavedChanges() {
		if (mUser == null) return false;
		return !stringEquals(mEditName.getText(), mUser.name)
				|| !stringEquals(mEditDescription.getText(), mUser.description)
				|| !stringEquals(mEditLocation.getText(), mUser.location)
				|| !stringEquals(mEditUrl.getText(), mUser.url);
	}

	private static boolean stringEquals(final CharSequence str1, final CharSequence str2) {
		if (str1 == null || str2 == null) return str1 == str2;
		return str1.toString().equals(str2.toString());
	}

	private class UpdateProfileBannerImageTaskInternal extends UpdateProfileBannerImageTask {

		public UpdateProfileBannerImageTaskInternal(final Context context, final AsyncTaskManager manager,
				final long account_id, final Uri image_uri, final boolean delete_image) {
			super(context, manager, account_id, image_uri, delete_image);
		}

		@Override
		protected void onPostExecute(final SingleResponse<Integer> result) {
			super.onPostExecute(result);
			setUpdateState(false);
			getUserInfo();
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			setUpdateState(true);
		}

	}

	private class UpdateProfileImageTaskInternal extends UpdateProfileImageTask {

		public UpdateProfileImageTaskInternal(final Context context, final AsyncTaskManager manager,
				final long account_id, final Uri image_uri, final boolean delete_image) {
			super(context, manager, account_id, image_uri, delete_image);
		}

		@Override
		protected void onPostExecute(final SingleResponse<User> result) {
			super.onPostExecute(result);
			if (result != null && result.data != null) {
				final boolean large_profile_image = getResources().getBoolean(R.bool.hires_profile_image);
				displayUser(new ParcelableUser(result.data, mAccountId, large_profile_image));
			}
			setUpdateState(false);
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			setUpdateState(true);
		}

	}

	class RemoveProfileBannerTaskInternal extends AsyncTask<Void, Void, SingleResponse<Boolean>> {

		private final long account_id;

		RemoveProfileBannerTaskInternal(final long account_id) {
			this.account_id = account_id;
		}

		@Override
		protected SingleResponse<Boolean> doInBackground(final Void... params) {
			return TwitterWrapper.deleteProfileBannerImage(EditUserProfileActivity.this, account_id);
		}

		@Override
		protected void onPostExecute(final SingleResponse<Boolean> result) {
			super.onPostExecute(result);
			if (result != null && result.data != null && result.data) {
				getUserInfo();
				Toast.makeText(EditUserProfileActivity.this, R.string.profile_banner_image_update_successfully,
						Toast.LENGTH_SHORT).show();
			} else {
				showErrorToast(EditUserProfileActivity.this, R.string.removing_profile_banner_image, result.exception,
						true);
			}
			setUpdateState(false);
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			setUpdateState(true);
		}

	}

	class UpdateProfileTaskInternal extends UpdateProfileTask {

		public UpdateProfileTaskInternal(final Context context, final AsyncTaskManager manager, final long account_id,
				final String name, final String url, final String location, final String description) {
			super(context, manager, account_id, name, url, location, description);
		}

		@Override
		protected void onPostExecute(final SingleResponse<User> result) {
			super.onPostExecute(result);
			if (result != null && result.data != null) {
				final boolean large_profile_image = getResources().getBoolean(R.bool.hires_profile_image);
				displayUser(new ParcelableUser(result.data, mAccountId, large_profile_image));
			}
			setUpdateState(false);
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			setUpdateState(true);
		}

	}
}
