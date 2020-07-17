package com.oasisfeng.island.model;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SearchView;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import com.oasisfeng.android.app.Activities;
import com.oasisfeng.android.base.Scopes;
import com.oasisfeng.android.databinding.recyclerview.ItemBinder;
import com.oasisfeng.android.os.UserHandles;
import com.oasisfeng.android.ui.Dialogs;
import com.oasisfeng.androidx.lifecycle.NonNullMutableLiveData;
import com.oasisfeng.common.app.BaseAppListViewModel;
import com.oasisfeng.island.analytics.Analytics;
import com.oasisfeng.island.controller.IslandAppClones;
import com.oasisfeng.island.controller.IslandAppControl;
import com.oasisfeng.island.data.IslandAppInfo;
import com.oasisfeng.island.data.IslandAppListProvider;
import com.oasisfeng.island.featured.FeaturedListViewModel;
import com.oasisfeng.island.greenify.GreenifyClient;
import com.oasisfeng.island.mobile.BR;
import com.oasisfeng.island.mobile.BuildConfig;
import com.oasisfeng.island.mobile.R;
import com.oasisfeng.island.settings.IslandNameManager;
import com.oasisfeng.island.shortcut.AbstractAppLaunchShortcut;
import com.oasisfeng.island.util.DevicePolicies;
import com.oasisfeng.island.util.Users;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

import static com.oasisfeng.island.analytics.Analytics.Param.ITEM_ID;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

/**
 * View model for apps
 *
 * Created by Oasis on 2015/7/7.
 */
@ParametersAreNonnullByDefault
public class AppListViewModel extends BaseAppListViewModel<AppViewModel> {

	private static final long QUERY_TEXT_DELAY = 300;	// The delay before typed query text is applied
	private static final String STATE_KEY_FILTER_HIDDEN_SYSTEM_APPS = "filter.hidden_sys";

	/** Workaround for menu res reference not supported by data binding */ public static @MenuRes int actions_menu = R.menu.app_actions;

	public interface Filter extends Predicate<IslandAppInfo> {
		Filter Island = app -> app.shouldShowAsEnabled() && app.isInstalled();
		Filter Mainland = app -> Users.isOwner(app.user) && (app.isSystem() || app.isInstalled());	// Including uninstalled system app
	}

	private Predicate<IslandAppInfo> activeFilters() {
		return mActiveFilters;
	}

	/** @see SearchView.OnQueryTextListener#onQueryTextChange(String) */
	public boolean onQueryTextChange(final String text) {
		mHandler.removeCallbacksAndMessages(mFilterText);		// In case like "A -> AB -> A" in a short time
		if (TextUtils.equals(text, mFilterText.getValue())) return true;
		HandlerCompat.postDelayed(mHandler, () -> mFilterText.setValue(text), mFilterText, QUERY_TEXT_DELAY);	// A short delay to avoid flickering during typing.
		return true;
	}

	/** @see SearchView.OnQueryTextListener#onQueryTextSubmit(String) */
	public void onQueryTextSubmit(final String text) {
		mChipsVisible.setValue(! TextUtils.isEmpty(text) || mFilterIncludeHiddenSystemApps.getValue());
		if (! TextUtils.equals(text, mFilterText.getValue())) {
			mHandler.removeCallbacksAndMessages(mFilterText);
			mFilterText.setValue(text);
		}
	}

	public void onSearchClick(final SearchView view) {
		view.setQuery(mFilterText.getValue(), false);
	}

	/** @see SearchView.OnCloseListener#onClose() */
	public boolean onSearchViewClose() {
		onQueryTextSubmit(mFilterText.getValue());
		return true;
	}

	public void onQueryTextCleared() {
		onQueryTextSubmit("");
	}

	private void updateAppList() {
		if (mFilterShared == null) return;		// When called by constructor   TODO: Obsolete?
		final UserHandle profile = mProfile;
		if (profile == null) return;
		Log.d(TAG, "Profile: " + UserHandles.getIdentifier(profile));

		final Filter profile_filter = Users.isOwner(profile) ? Filter.Mainland : Filter.Island;
		Predicate<IslandAppInfo> filters = mFilterShared.and(profile_filter);
		if (! mFilterIncludeHiddenSystemApps.getValue())
			filters = filters.and(app -> ! app.isSystem() || app.isInstalled() && app.isLaunchable());
		final String text = mFilterText.getValue();
		if (! TextUtils.isEmpty(text)) {
			if (text.startsWith("package:")) {
				final String pkg = text.substring(8);
				filters = filters.and(app -> app.packageName.equals(pkg));
			} else {    // TODO: Support T9 Pinyin
				final String text_lc = text.toLowerCase();
				filters = filters.and(app -> app.packageName.toLowerCase().contains(text_lc) || app.getLabel().toLowerCase().contains(text_lc));
			}
		}
		mActiveFilters = filters;

		final AppViewModel selected = mSelection.getValue();
		clearSelection();

		IslandAppInfo.cacheLaunchableApps(requireNonNull(mAppListProvider.getContext()));	// Performance optimization
		final List<AppViewModel> apps = mAppListProvider.installedApps(profile).filter(filters).map(AppViewModel::new).collect(toList());
		IslandAppInfo.invalidateLaunchableAppsCache();
		replaceApps(apps);

		if (selected != null) for (final AppViewModel app : apps)
			if (app.info().packageName.equals(selected.info().packageName)) {
				setSelection(app);
				break;
			}
	}

	public AppListViewModel() {
		super(AppViewModel.class);
		mSelection.observeForever(selection -> updateActions());
	}

	public void onTabSwitched(final Context context, final TabLayout tabs, final TabLayout.Tab tab) {
		final int position = tab.getPosition();
		if (position == 0) {    // Discovery
			mProfile = null;
			mFeatured.visible.setValue(Boolean.TRUE);
			mFeatured.update(context);
			mChipsVisible.setValue(false);
			return;
		} else mFeatured.visible.setValue(Boolean.FALSE);

		if (position >= 2) {    // One of the Islands
			final Object tag = tab.getTag();
			if (tag instanceof UserHandle) {
				final UserHandle profile = (UserHandle) tag;
				if (Users.isProfileManagedByIsland(profile)) {
					mProfile = profile;
					updateAppList();
					return;
				}
			}
		}
		tabs.selectTab(tabs.getTabAt(1));   // Switch back to Mainland
		mProfile = Users.owner;
		updateAppList();
	}

	public void attach(final Context context, final Menu actions, final TabLayout tabs, final @Nullable Bundle state) {
		mAppListProvider = IslandAppListProvider.getInstance(context);
		mOwnerUserManaged = new DevicePolicies(context).isProfileOrDeviceOwnerOnCallingUser();
		mActions = actions;
		mFilterShared = IslandAppListProvider.excludeSelf(context);

		tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
			@Override public void onTabSelected(final TabLayout.Tab tab) { onTabSwitched(context, tabs, tab); }
			@Override public void onTabUnselected(final TabLayout.Tab tab) {}
			@Override public void onTabReselected(final TabLayout.Tab tab) {}
		});
		// Tab "Discovery" and "Mainland" are always present
		tabs.addTab(tabs.newTab().setText(R.string.tab_discovery));
		tabs.addTab(tabs.newTab().setText(R.string.tab_mainland));
		UserHandle defaultProfile = Users.profile;		// Default
		if (defaultProfile != null) {
			if (state != null) {
				final UserHandle profile = state.getParcelable(Intent.EXTRA_USER);
				if (profile != null && (Users.isOwner(profile) || Users.isProfileManagedByIsland(profile)))
					defaultProfile = profile;
				else defaultProfile = Users.owner;
			}
			mProfile = defaultProfile;
			mFilterIncludeHiddenSystemApps.setValue(state != null && state.getBoolean(STATE_KEY_FILTER_HIDDEN_SYSTEM_APPS));

			final Map<UserHandle, String> names = IslandNameManager.getAllNames(context);
			for (final UserHandle profile : names.keySet()) {
				final TabLayout.Tab tab = tabs.newTab().setTag(profile).setText(names.get(profile));
				tabs.addTab(tab);
				if (profile.equals(defaultProfile)) tabs.selectTab(tab);
			}
			if (tabs.getTabCount() > 3) tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
		} else mProfile = Users.owner;

		if (state != null) {
			final String filter_text = state.getString(SearchManager.QUERY);
			if (filter_text != null && ! filter_text.isEmpty()) onQueryTextSubmit(filter_text);
		}
		// Start observation after initial value is set.
		mFilterIncludeHiddenSystemApps.observeForever(filter -> updateAppList());
		mFilterText.observeForever(text -> updateAppList());
		updateAppList();
	}

	public void onSaveInstanceState(final Bundle saved) {
		saved.putParcelable(Intent.EXTRA_USER, mProfile);
		saved.putBoolean(STATE_KEY_FILTER_HIDDEN_SYSTEM_APPS, mFilterIncludeHiddenSystemApps.getValue());
		final String text = mFilterText.getValue();
		if (! text.isEmpty()) saved.putString(SearchManager.QUERY, text);
	}

	private void updateActions() {
		final AppViewModel selection = mSelection.getValue();
		if (selection == null) return;
		final IslandAppInfo app = selection.info();
		Analytics.$().trace("app", app.packageName).trace("user", Users.toId(app.user)).trace("hidden", app.isHidden())
				.trace("system", app.isSystem()).trace("critical", app.isCritical());
		final int num_profiles = Users.getProfilesManagedByIsland().size();
		final boolean exclusive = mAppListProvider.isExclusive(app);

		final boolean system = app.isSystem(), installed = app.isInstalled(),
				in_owner = Users.isOwner(app.user), is_managed = ! in_owner || mOwnerUserManaged;
		mActions.findItem(R.id.menu_freeze).setVisible(installed && is_managed && ! app.isHidden() && app.enabled);
		mActions.findItem(R.id.menu_unfreeze).setVisible(installed && is_managed && app.isHidden());
		mActions.findItem(R.id.menu_clone).setVisible(in_owner && num_profiles > 0 && (num_profiles > 1 || exclusive));
		mActions.findItem(R.id.menu_clone_back).setVisible(! in_owner && exclusive);
		mActions.findItem(R.id.menu_reinstall).setVisible(! installed);
		mActions.findItem(R.id.menu_remove).setVisible(installed && (exclusive ? system : (! system || app.shouldShowAsEnabled())));	// Disabled system app is treated as "removed".
		mActions.findItem(R.id.menu_uninstall).setVisible(installed && exclusive && ! system);	// "Uninstall" for exclusive user app, "Remove" for exclusive system app.
		mActions.findItem(R.id.menu_shortcut).setVisible(installed && is_managed && app.isLaunchable() && app.enabled);
		mActions.findItem(R.id.menu_greenify).setVisible(installed && is_managed && app.enabled);

		if (BuildConfig.DEBUG) mActions.findItem(R.id.menu_suspend).setVisible(true).setTitle(app.isSuspended() ? "Unsuspend" : "Suspend");
	}

	public void onPackagesUpdate(final Collection<IslandAppInfo> apps) {
		final Predicate<IslandAppInfo> filters = activeFilters();
		for (final IslandAppInfo app : apps)
			if (filters.test(app)) {
				putApp(app.packageName, new AppViewModel(app));
			} else removeApp(app.packageName, app.user);
		updateActions();
	}

	private void removeApp(final String pkg, final UserHandle user) {
		final AppViewModel app = getApp(pkg);
		if (app != null && app.info().user.equals(user)) super.removeApp(pkg);
	}

	public void onPackagesRemoved(final Collection<IslandAppInfo> apps) {
		final Predicate<IslandAppInfo> filters = activeFilters();
		for (final IslandAppInfo app : apps)
			if (filters.test(app)) removeApp(app.packageName);
		updateActions();
	}

	public void onItemLaunchIconClick(final Context context, final IslandAppInfo app) {
		IslandAppControl.launch(context, this, app);
	}

	public boolean onActionClick(final Context context, final MenuItem item) {
		final AppViewModel selection = mSelection.getValue();
		if (selection == null) return false;
		final IslandAppInfo app = selection.info();
		final String pkg = app.packageName;

		final int id = item.getItemId();
		if (id == R.id.menu_clone) {
			requestToCloneApp(context, app);
			clearSelection();
		} else if (id == R.id.menu_clone_back) {
			Activities.startActivity(context, new Intent(Intent.ACTION_INSTALL_PACKAGE, Uri.fromParts("package", pkg, null)));
			Analytics.$().event("action_install_outside").with(ITEM_ID, pkg).send();
			clearSelection();
		} else if (id == R.id.menu_freeze) {// Select the next alive app, or clear selection.
			Analytics.$().event("action_freeze").with(ITEM_ID, pkg).send();

			final Activity activity = Activities.findActivityFrom(context);
			if (activity != null && IslandAppListProvider.getInstance(context).isCritical(pkg)) {
				Dialogs.buildAlert(activity, R.string.dialog_title_warning, R.string.dialog_critical_app_warning)
						.withCancelButton().withOkButton(() -> freezeApp(context, selection)).show();
			} else freezeApp(context, selection);
		} else if (id == R.id.menu_unfreeze) {
			Analytics.$().event("action_unfreeze").with(ITEM_ID, pkg).send();
			IslandAppControl.unfreeze(this, app).exceptionally(t -> {
				reportAndShowToastForInternalException(context, "Error unfreezing app: " + app.packageName, t);
				return false;
			}).thenAccept(result -> {
				if (! result) return;
				refreshAppStateAsSysBugWorkaround(context, app);
				clearSelection();
			});
		} else if (id == R.id.menu_app_settings) {
			IslandAppControl.launchExternalAppSettings(this, app);
		} else if (id == R.id.menu_remove || id == R.id.menu_uninstall) {
			IslandAppControl.requestRemoval(this, requireNonNull(Activities.findActivityFrom(context)), selection.info());
		} else if (id == R.id.menu_reinstall) {
			onReinstallRequested(context);
		} else if (id == R.id.menu_shortcut) {
			onShortcutRequested(context);
		} else if (id == R.id.menu_greenify) {
			onGreenifyRequested(context);
		} else if (id == R.id.menu_suspend) {
			IslandAppControl.setSuspended(this, app, ! app.isSuspended()).thenAccept(done ->
					Toast.makeText(context, done ? "Done" : "Failed", Toast.LENGTH_SHORT).show());
		}
		return true;
	}

	private void requestToCloneApp(final Context context, final IslandAppInfo app) {
		final Map<UserHandle, String> targets = IslandNameManager.getAllNames(context);
		if (targets.isEmpty()) throw new IllegalStateException("No Island");
		final Set<UserHandle> profiles = targets.keySet();

		if (profiles.size() == 1) requestToCloneAppToProfile(context, app, profiles.iterator().next());

		for (final Iterator<UserHandle> iterator = profiles.iterator(); iterator.hasNext(); ) {
			final UserHandle profile = iterator.next();
			final IslandAppInfo appInProfile = mAppListProvider.get(app.packageName, profile);
			if (appInProfile != null && app.shouldShowAsEnabled()) iterator.remove();  // Already cloned to target profile
		}

		final String[] names = targets.values().toArray(new String[0]);
		Dialogs.buildList(requireNonNull(Activities.findActivityFrom(context)), R.string.prompt_clone_app_to, names, (dialog, which) ->
				requestToCloneAppToProfile(context, app, new ArrayList<>(profiles).get(which))).show();
	}

	private void requestToCloneAppToProfile(final Context context, final IslandAppInfo app, final UserHandle profile) {
		final String pkg = app.packageName;
		final IslandAppInfo target = IslandAppListProvider.getInstance(context).get(pkg, profile);
		if (target != null && target.isHiddenSysIslandAppTreatedAsDisabled()) {	// Frozen system app shown as disabled, just unfreeze it.
			IslandAppControl.unfreezeInitiallyFrozenSystemApp(this, app).thenAccept(unfrozen -> {
				if (unfrozen) Toast.makeText(context, context.getString(R.string.toast_successfully_cloned, app.getLabel()), Toast.LENGTH_SHORT).show();
			}).exceptionally(t -> { reportAndShowToastForInternalException(context, "Error unfreezing app: " + pkg, t); return null; });
		} else if (target != null && target.isInstalled() && ! target.enabled) {	// Disabled system app is shown as "removed" (not cloned)
			IslandAppControl.launchSystemAppSettings(this, target);
			Toast.makeText(context, R.string.toast_enable_disabled_system_app, Toast.LENGTH_SHORT).show();
		} else IslandAppClones.cloneApp(this, app, profile);
	}

	private void freezeApp(final Context context, final AppViewModel app_vm) {
		final IslandAppInfo app = app_vm.info();
		IslandAppControl.freeze(this, app).thenAccept(frozen -> {
			if (frozen) {
				// Select the next app for convenient continuous freezing.
				final int next_index = indexOf(app_vm) + 1;
				final AppViewModel next;
				if (next_index < size() && (next = getAppAt(next_index)).state == AppViewModel.State.Alive) setSelection(next);
				else clearSelection();
			}
			refreshAppStateAsSysBugWorkaround(context, app);
		});
	}

	private void onShortcutRequested(final Context context) {
		final AppViewModel app_vm = mSelection.getValue();
		if (app_vm == null) return;
		final IslandAppInfo app= app_vm.info();
		final String pkg = app.packageName;
		Analytics.$().event("action_create_shortcut").with(ITEM_ID, pkg).send();
		final String shortcut_prefix = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.key_launch_shortcut_prefix), context.getString(R.string.default_launch_shortcut_prefix));
		final Boolean result = AbstractAppLaunchShortcut.createOnLauncher(context, pkg, app, app.user, shortcut_prefix + app.getLabel(), app.icon);
		if (result == null || result) Toast.makeText(context, R.string.toast_shortcut_request_sent, Toast.LENGTH_SHORT).show();	// MIUI has no UI for shortcut pinning.
		else Toast.makeText(context, R.string.toast_shortcut_failed, Toast.LENGTH_LONG).show();
	}

	private void onGreenifyRequested(final Context context) {
		if (mSelection.getValue() == null) return;
		final IslandAppInfo app = mSelection.getValue().info();
		Analytics.$().event("action_greenify").with(ITEM_ID, app.packageName).send();

		final String mark = "greenify-explained";
		final Boolean greenify_ready = GreenifyClient.checkGreenifyVersion(context);
		final boolean greenify_installed = greenify_ready != null;
		final boolean unavailable_or_version_too_low = greenify_ready == null || ! greenify_ready;
		if (unavailable_or_version_too_low || ! Scopes.app(context).isMarked(mark)) {
			String message = context.getString(R.string.dialog_greenify_explanation);
			if (greenify_installed && unavailable_or_version_too_low)
				message += "\n\n" + context.getString(R.string.dialog_greenify_version_too_low);
			final int button = ! greenify_installed ? R.string.action_install : ! greenify_ready ? R.string.action_upgrade : R.string.action_continue;
			new AlertDialog.Builder(context).setTitle(R.string.dialog_greenify_title).setMessage(message).setPositiveButton(button, (d, w) -> {
				if (! unavailable_or_version_too_low) {
					Scopes.app(context).markOnly(mark);
					greenify(context, app);
				} else GreenifyClient.openInAppMarket(context);
			}).show();
		} else greenify(context, app);
	}

	private static void greenify(final Context context, final IslandAppInfo app) {
		if (! GreenifyClient.greenify(context, app.packageName, app.user))
			Toast.makeText(context, R.string.toast_greenify_failed, Toast.LENGTH_LONG).show();
	}

	private void onReinstallRequested(final Context context) {
		if (mSelection.getValue() == null) return;
		final IslandAppInfo app = mSelection.getValue().info();
		final Activity activity = Activities.findActivityFrom(context);
		if (activity == null) reinstallSystemApp(context, app);
		else Dialogs.buildAlert(activity, R.string.dialog_title_warning, R.string.dialog_reinstall_system_app_warning)
				.withCancelButton().setPositiveButton(R.string.action_continue, (d, w) -> reinstallSystemApp(context, app)).show();
	}

	private static void reinstallSystemApp(final Context context, final IslandAppInfo app) {
		final DevicePolicies policies = new DevicePolicies(context);
		policies.execute(DevicePolicyManager::enableSystemApp, app.packageName);
	}

	/** Possible 10s delay before the change broadcast could be received (due to Android issue 225880), so we force a refresh immediately. */
	private static void refreshAppStateAsSysBugWorkaround(final Context context, final IslandAppInfo app) {
		IslandAppListProvider.getInstance(context).refreshPackage(app.packageName, app.user, false);
	}

	public final void onItemClick(final AppViewModel clicked) {
		setSelection(clicked != mSelection.getValue() ? clicked : null);	// Click the selected one to deselect
	}

	@SuppressWarnings("MethodMayBeStatic") public final void onBottomSheetClick(final View view) {
		final BottomSheetBehavior<View> bottom_sheet = BottomSheetBehavior.from(view);
		bottom_sheet.setState(BottomSheetBehavior.STATE_EXPANDED);
	}

	private static void reportAndShowToastForInternalException(final Context context, final String log, final Throwable t) {
		Analytics.$().logAndReport(TAG, log, t);
		Toast.makeText(context, "Internal error: " + t.getMessage(), Toast.LENGTH_LONG).show();
	}

	@Override public @NonNull String getTag() { return TAG; }

	/* Parcelable */

	public final BottomSheetBehavior.BottomSheetCallback bottom_sheet_callback = new BottomSheetBehavior.BottomSheetCallback() {

		@Override public void onStateChanged(@NonNull final View bottom_sheet, final int new_state) {
			if (new_state == BottomSheetBehavior.STATE_HIDDEN) clearSelection();
			else bottom_sheet.bringToFront();	// Force a lift due to bottom sheet appearing underneath BottomNavigationView on some devices, despite the layout order or elevation.
		}

		@Override public void onSlide(@NonNull final View bottomSheet, final float slideOffset) {}
	};

	public final ItemBinder<AppViewModel> item_binder = (container, model, item) -> {
		item.setVariable(BR.app, model);
		item.setVariable(BR.apps, this);
	};

	public FeaturedListViewModel mFeatured;
	/* Attachable fields */
	private IslandAppListProvider mAppListProvider;
	private Menu mActions;
	/* Parcelable fields */
	public UserHandle mProfile = null;
	public final NonNullMutableLiveData<Boolean> mFilterIncludeHiddenSystemApps = new NonNullMutableLiveData<>(false);
	public final NonNullMutableLiveData<String> mFilterText = new NonNullMutableLiveData<>("");
	/* Transient fields */
	public final NonNullMutableLiveData<Boolean> mChipsVisible = new NonNullMutableLiveData<>(false);
	private Predicate<IslandAppInfo> mFilterShared;			// All other filters to apply always
	private boolean mOwnerUserManaged;
	private Predicate<IslandAppInfo> mActiveFilters;		// The active composite filters
	private final Handler mHandler = new Handler();

	private static final String TAG = "Island.Apps";
}
