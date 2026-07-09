package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.vpn.SingBoxManager;
import org.telegram.messenger.vpn.VpnController;
import org.telegram.messenger.vpn.VpnKeyInfo;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;

import java.util.ArrayList;
import java.util.List;

public class VpnListActivity extends BaseFragment implements VpnController.Listener {

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private NumberTextView selectedCountTextView;
    private ActionBarMenuItem shareMenuItem;
    private ActionBarMenuItem deleteMenuItem;
    private final List<VpnKeyInfo> selectedItems = new ArrayList<>();

    private int rowCount;
    @Keep private int useVpnRow;
    @Keep private int autoSwitchRow;
    private int autoSwitchTimeoutRow;
    private int autoSwitchInfoRow;
    private int useVpnShadowRow;
    private int connectionsHeaderRow;
    private int vpnStartRow;
    private int vpnEndRow;
    @Keep private int addVpnRow;
    private int vpnShadowRow;
    private int infoRow;

    private static final int MENU_DELETE = 0;
    private static final int MENU_SHARE = 1;
    private static final int help_button = 2;

    private static final int VIEW_TYPE_SHADOW = 0;
    private static final int VIEW_TYPE_SETTING = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_CHECK = 3;
    private static final int VIEW_TYPE_INFO = 4;
    private static final int VIEW_TYPE_DETAIL = 5;
    private static final int VIEW_TYPE_SLIDER = 6;

    private static final int PAYLOAD_SELECTION_CHANGED = 0;
    private static final int PAYLOAD_SELECTION_MODE_CHANGED = 1;

    @Override
    public boolean onFragmentCreate() {
        VpnController.getInstance().load();
        VpnController.getInstance().addListener(this);
        updateRows(false);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        VpnController.getInstance().removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.VpnSettings));
        actionBar.createMenu().addItem(help_button, R.drawable.msg_info);

        ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountTextView = new NumberTextView(actionMode.getContext());
        selectedCountTextView.setTextSize(18);
        selectedCountTextView.setTypeface(AndroidUtilities.bold());
        selectedCountTextView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountTextView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        selectedCountTextView.setOnTouchListener((v, event) -> true);
        shareMenuItem = actionMode.addItemWithWidth(MENU_SHARE, R.drawable.msg_share, AndroidUtilities.dp(54));
        shareMenuItem.setContentDescription(getString(R.string.ShareFile));
        deleteMenuItem = actionMode.addItemWithWidth(MENU_DELETE, R.drawable.msg_delete, AndroidUtilities.dp(54));
        deleteMenuItem.setContentDescription(getString(R.string.Delete));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (!selectedItems.isEmpty()) {
                        listAdapter.clearSelected();
                    } else {
                        finishFragment();
                    }
                } else if (id == help_button) {
                    showSupportedProtocols();
                } else if (id == MENU_DELETE) {
                    deleteSelected();
                } else if (id == MENU_SHARE) {
                    shareSelected(context);
                }
            }
        });

        listAdapter = new ListAdapter(context);
        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(view, position));
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= vpnStartRow && vpnStartRow >= 0 && position < vpnEndRow) {
                listAdapter.toggleSelected(position);
                return true;
            }
            return false;
        });
        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selectedItems.isEmpty()) {
            if (invoked) {
                listAdapter.clearSelected();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void onRowClick(View view, int position) {
        VpnController c = VpnController.getInstance();
        if (position == useVpnRow) {
            if (c.currentVpn == null) {
                return;
            }
            boolean enable = !c.isEnabled();
            c.setEnabled(enable);
            ((TextCheckCell) view).setChecked(enable);
            updateRows(true);
        } else if (position == autoSwitchRow) {
            boolean value = !c.isAutoSwitch();
            c.setAutoSwitch(value);
            ((TextCheckCell) view).setChecked(value);
            updateRows(true);
        } else if (position == addVpnRow) {
            presentFragment(new VpnSettingsActivity());
        } else if (position >= vpnStartRow && vpnStartRow >= 0 && position < vpnEndRow) {
            if (!selectedItems.isEmpty()) {
                listAdapter.toggleSelected(position);
                return;
            }
            VpnKeyInfo info = c.vpnList.get(position - vpnStartRow);
            c.selectVpn(info);
            c.setEnabled(true);
            updateRows(true);
        }
    }

    private void deleteSelected() {
        if (getParentActivity() == null || selectedItems.isEmpty()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.VpnDeleteTitle));
        builder.setMessage(getString(R.string.VpnDeleteText));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            for (VpnKeyInfo info : new ArrayList<>(selectedItems)) {
                VpnController.getInstance().deleteVpn(info);
            }
            if (listAdapter != null) {
                listAdapter.clearSelected();
            }
            updateRows(true);
        });
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void shareSelected(Context context) {
        if (selectedItems.isEmpty()) {
            return;
        }
        StringBuilder keys = new StringBuilder();
        for (VpnKeyInfo info : selectedItems) {
            if (keys.length() > 0) {
                keys.append("\n\n");
            }
            keys.append(info.key);
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, keys.toString());
        Intent chooserIntent = Intent.createChooser(shareIntent, getString(R.string.ShareFile));
        chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooserIntent);
        if (listAdapter != null) {
            listAdapter.clearSelected();
        }
    }

    private void showSupportedProtocols() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.VpnSupportedProtocols));
        builder.setMessage(getString(R.string.VpnSupportedProtocolsInfo));
        builder.setPositiveButton(getString(R.string.OK), null);
        showDialog(builder.create());
    }

    private void updateRows(boolean notify) {
        VpnController c = VpnController.getInstance();
        rowCount = 0;
        useVpnRow = rowCount++;
        if (c.isEnabled()) {
            autoSwitchRow = rowCount++;
            if (c.isAutoSwitch()) {
                autoSwitchTimeoutRow = rowCount++;
                autoSwitchInfoRow = rowCount++;
            } else {
                autoSwitchTimeoutRow = -1;
                autoSwitchInfoRow = -1;
            }
        } else {
            autoSwitchRow = -1;
            autoSwitchTimeoutRow = -1;
            autoSwitchInfoRow = -1;
        }
        useVpnShadowRow = autoSwitchInfoRow == -1 ? rowCount++ : -1;
        connectionsHeaderRow = rowCount++;
        if (!c.vpnList.isEmpty()) {
            vpnStartRow = rowCount;
            rowCount += c.vpnList.size();
            vpnEndRow = rowCount;
        } else {
            vpnStartRow = -1;
            vpnEndRow = -1;
        }
        addVpnRow = rowCount++;
        vpnShadowRow = rowCount++;
        infoRow = rowCount++;
        if (notify && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    // VpnController.Listener (already marshalled to UI thread by the controller)
    @Override
    public void onVpnListChanged() {
        AndroidUtilities.runOnUIThread(() -> updateRows(true));
    }

    @Override
    public void onVpnStateChanged(int state, String message) {
        AndroidUtilities.runOnUIThread(() -> {
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
    }

    private class VpnDetailCell extends FrameLayout {

        private final TextView textView;
        private final TextView valueTextView;
        private final ImageView checkImageView;
        private final CheckBox2 checkBox;
        private VpnKeyInfo currentInfo;
        private Drawable checkDrawable;
        private boolean isSelected;
        private boolean isSelectionEnabled;
        private int color;

        public VpnDetailCell(Context context) {
            super(context);

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 56 : 21), 10, (LocaleController.isRTL ? 21 : 56), 0));

            valueTextView = new TextView(context);
            valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            valueTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            valueTextView.setLines(1);
            valueTextView.setMaxLines(1);
            valueTextView.setSingleLine(true);
            valueTextView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
            valueTextView.setEllipsize(TextUtils.TruncateAt.END);
            addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 56 : 21), 35, (LocaleController.isRTL ? 21 : 56), 0));

            checkImageView = new ImageView(context);
            checkImageView.setImageResource(R.drawable.msg_settings);
            checkImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), PorterDuff.Mode.MULTIPLY));
            checkImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            checkImageView.setPadding(AndroidUtilities.dp(11), AndroidUtilities.dp(11), AndroidUtilities.dp(11), AndroidUtilities.dp(11));
            checkImageView.setContentDescription(getString(R.string.Edit));
            addView(checkImageView, LayoutHelper.createFrame(54, 54, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 4, 0, 6, 0));
            checkImageView.setOnClickListener(v -> {
                if (currentInfo != null) {
                    presentFragment(new VpnSettingsActivity(currentInfo));
                }
            });

            checkBox = new CheckBox2(context, 21);
            checkBox.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
            checkBox.setDrawBackgroundAsArc(14);
            checkBox.setVisibility(GONE);
            addView(checkBox, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 16, 0, 8, 0));

            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + 1, MeasureSpec.EXACTLY));
        }

        public void setInfo(VpnKeyInfo info) {
            currentInfo = info;
            textView.setText(info.getDisplayName());
            updateStatus();
        }

        public void updateStatus() {
            VpnController c = VpnController.getInstance();
            int colorKey;
            if (currentInfo == c.currentVpn && c.isEnabled()) {
                int state = c.getState();
                if (state == SingBoxManager.STATE_CONNECTED) {
                    colorKey = Theme.key_windowBackgroundWhiteBlueText6;
                    if (currentInfo.ping != 0) {
                        valueTextView.setText(getString(R.string.Connected) + ", " + LocaleController.formatString("Ping", R.string.Ping, currentInfo.ping));
                    } else {
                        valueTextView.setText(getString(R.string.Connected));
                    }
                } else if (state == SingBoxManager.STATE_ERROR) {
                    colorKey = Theme.key_text_RedRegular;
                    valueTextView.setText(getString(R.string.Unavailable));
                } else {
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                    valueTextView.setText(getString(R.string.Connecting));
                }
            } else {
                colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                valueTextView.setText(currentInfo.getType());
            }
            color = Theme.getColor(colorKey);
            valueTextView.setTextColor(color);
            if (checkDrawable != null) {
                checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
            }
        }

        public void setChecked(boolean checked) {
            if (checked) {
                if (checkDrawable == null) {
                    checkDrawable = getResources().getDrawable(R.drawable.proxy_check).mutate();
                }
                if (checkDrawable != null) {
                    checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                }
                if (LocaleController.isRTL) {
                    valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, checkDrawable, null);
                } else {
                    valueTextView.setCompoundDrawablesWithIntrinsicBounds(checkDrawable, null, null, null);
                }
            } else {
                valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            }
        }

        public void setItemSelected(boolean selected, boolean animated) {
            if (selected == isSelected && animated) {
                return;
            }
            isSelected = selected;
            checkBox.setChecked(selected, animated);
        }

        public void setSelectionEnabled(boolean enabled, boolean animated) {
            if (isSelectionEnabled == enabled && animated) {
                return;
            }
            isSelectionEnabled = enabled;
            float fromX = 0, toX = LocaleController.isRTL ? -AndroidUtilities.dp(32) : AndroidUtilities.dp(32);
            if (!animated) {
                float x = enabled ? toX : fromX;
                textView.setTranslationX(x);
                valueTextView.setTranslationX(x);
                checkImageView.setTranslationX(x);
                checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);
                checkImageView.setVisibility(enabled ? GONE : VISIBLE);
                checkImageView.setAlpha(1f);
                checkBox.setVisibility(enabled ? VISIBLE : GONE);
                checkBox.setAlpha(1f);
                checkBox.setScaleX(1f);
                checkBox.setScaleY(1f);
            } else {
                ValueAnimator animator = ValueAnimator.ofFloat(enabled ? 0 : 1, enabled ? 1 : 0).setDuration(200);
                animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                animator.addUpdateListener(animation -> {
                    float val = (float) animation.getAnimatedValue();
                    float x = AndroidUtilities.lerp(fromX, toX, val);
                    textView.setTranslationX(x);
                    valueTextView.setTranslationX(x);
                    checkImageView.setTranslationX(x);
                    checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);
                    float scale = 0.5f + val * 0.5f;
                    checkBox.setScaleX(scale);
                    checkBox.setScaleY(scale);
                    checkBox.setAlpha(val);
                    scale = 0.5f + (1f - val) * 0.5f;
                    checkImageView.setScaleX(scale);
                    checkImageView.setScaleY(scale);
                    checkImageView.setAlpha(1f - val);
                });
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        if (enabled) {
                            checkBox.setAlpha(0f);
                            checkBox.setVisibility(VISIBLE);
                        } else {
                            checkImageView.setAlpha(0f);
                            checkImageView.setVisibility(VISIBLE);
                        }
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (enabled) {
                            checkImageView.setVisibility(GONE);
                        } else {
                            checkBox.setVisibility(GONE);
                        }
                    }
                });
                animator.start();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateStatus();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        public void toggleSelected(int position) {
            if (position < vpnStartRow || vpnStartRow < 0 || position >= vpnEndRow) {
                return;
            }
            VpnKeyInfo info = VpnController.getInstance().vpnList.get(position - vpnStartRow);
            if (selectedItems.contains(info)) {
                selectedItems.remove(info);
            } else {
                selectedItems.add(info);
            }
            notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        public void clearSelected() {
            selectedItems.clear();
            if (vpnStartRow >= 0) {
                notifyItemRangeChanged(vpnStartRow, vpnEndRow - vpnStartRow, PAYLOAD_SELECTION_CHANGED);
            }
            checkActionMode();
        }

        private void checkActionMode() {
            int selectedCount = selectedItems.size();
            boolean actionModeShowed = actionBar.isActionModeShowed();
            if (selectedCount > 0) {
                selectedCountTextView.setNumber(selectedCount, actionModeShowed);
                if (!actionModeShowed) {
                    actionBar.showActionMode();
                    if (vpnStartRow >= 0) {
                        notifyItemRangeChanged(vpnStartRow, vpnEndRow - vpnStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                    }
                }
            } else if (actionModeShowed) {
                actionBar.hideActionMode();
                if (vpnStartRow >= 0) {
                    notifyItemRangeChanged(vpnStartRow, vpnEndRow - vpnStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                }
            }
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == useVpnRow || position == autoSwitchRow || position == addVpnRow
                    || (position >= vpnStartRow && vpnStartRow >= 0 && position < vpnEndRow);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == useVpnShadowRow || position == vpnShadowRow) {
                return VIEW_TYPE_SHADOW;
            } else if (position == useVpnRow || position == autoSwitchRow) {
                return VIEW_TYPE_CHECK;
            } else if (position == connectionsHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == infoRow || position == autoSwitchInfoRow) {
                return VIEW_TYPE_INFO;
            } else if (position == autoSwitchTimeoutRow) {
                return VIEW_TYPE_SLIDER;
            } else if (position >= vpnStartRow && vpnStartRow >= 0 && position < vpnEndRow) {
                return VIEW_TYPE_DETAIL;
            } else {
                return VIEW_TYPE_SETTING;
            }
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_SLIDER:
                    view = new SlideChooseView(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_DETAIL:
                    view = new VpnDetailCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_SETTING:
                default:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            VpnController c = VpnController.getInstance();
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == useVpnRow) {
                        cell.setTextAndCheck(getString(R.string.UseVpn), c.isEnabled(), autoSwitchRow != -1);
                    } else if (position == autoSwitchRow) {
                        cell.setTextAndCheck(getString(R.string.VpnAutoSwitch), c.isAutoSwitch(), c.isAutoSwitch());
                    }
                    break;
                }
                case VIEW_TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(getString(R.string.VpnConnections));
                    break;
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == autoSwitchInfoRow) {
                        cell.setText(getString(R.string.VpnAutoSwitchInfo));
                    } else {
                        cell.setText(getString(R.string.VpnInfo));
                    }
                    break;
                }
                case VIEW_TYPE_SLIDER: {
                    SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                    String[] values = new String[VpnController.AUTOSWITCH_TIMEOUTS.length];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = LocaleController.formatString(R.string.ProxyRotationTimeoutSeconds, VpnController.AUTOSWITCH_TIMEOUTS[i]);
                    }
                    chooseView.setCallback(i -> VpnController.getInstance().setAutoSwitchTimeoutIndex(i));
                    chooseView.setOptions(c.getAutoSwitchTimeoutIndex(), values);
                    break;
                }
                case VIEW_TYPE_DETAIL: {
                    VpnDetailCell cell = (VpnDetailCell) holder.itemView;
                    VpnKeyInfo info = c.vpnList.get(position - vpnStartRow);
                    cell.setInfo(info);
                    cell.setChecked(c.currentVpn == info);
                    cell.setItemSelected(selectedItems.contains(info), false);
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), false);
                    break;
                }
                case VIEW_TYPE_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == addVpnRow) {
                        cell.setText(getString(R.string.AddVpn), false);
                    }
                    break;
                }
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List payloads) {
            if (holder.getItemViewType() == VIEW_TYPE_DETAIL && !payloads.isEmpty()) {
                VpnDetailCell cell = (VpnDetailCell) holder.itemView;
                VpnKeyInfo info = VpnController.getInstance().vpnList.get(position - vpnStartRow);
                if (payloads.contains(PAYLOAD_SELECTION_CHANGED)) {
                    cell.setItemSelected(selectedItems.contains(info), true);
                }
                if (payloads.contains(PAYLOAD_SELECTION_MODE_CHANGED)) {
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), true);
                }
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }
    }
}
