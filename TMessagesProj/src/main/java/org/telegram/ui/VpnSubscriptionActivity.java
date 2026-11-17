package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.vpn.SingBoxManager;
import org.telegram.messenger.vpn.VpnController;
import org.telegram.messenger.vpn.VpnKeyInfo;
import org.telegram.messenger.vpn.VpnSubscription;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;

import java.util.ArrayList;
import java.util.List;

/**
 * One subscription: update it, choose how often the running group tests its servers, exclude
 * servers by name, and see each server's last test result. The servers come from the link and are
 * read-only here; the core picks the fastest of them by itself.
 */
public class VpnSubscriptionActivity extends BaseFragment implements VpnController.Listener {

    private static final int MENU_RENAME = 1;
    private static final int MENU_DELETE = 2;

    private static final int VIEW_TYPE_SHADOW = 0;
    private static final int VIEW_TYPE_SETTING = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_INFO = 3;
    private static final int VIEW_TYPE_SLIDER = 4;
    private static final int VIEW_TYPE_SERVER = 5;

    private final VpnSubscription sub;
    private ListAdapter adapter;
    private final List<VpnKeyInfo> servers = new ArrayList<>();

    private int rowCount;
    private int updateRow;
    private int updateInfoRow;
    private int intervalHeaderRow;
    private int intervalRow;
    private int intervalInfoRow;
    private int excludeRow;
    private int excludeInfoRow;
    private int serversHeaderRow;
    private int fastestRow;
    private int serversStartRow;
    private int serversEndRow;
    private int serversInfoRow;

    public VpnSubscriptionActivity(VpnSubscription sub) {
        this.sub = sub;
    }

    @Override
    public boolean onFragmentCreate() {
        VpnController.getInstance().addListener(this);
        updateRows();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        VpnController.getInstance().removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        // With a server picked the group runs no tests of its own after the first round, so the
        // delays on this screen would be old. One round when the screen opens.
        if (sub.pinnedName != null && VpnController.getInstance().isRunning(sub)) {
            SingBoxManager.getInstance().requestUrlTest();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(sub.getDisplayName());
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_RENAME, R.drawable.msg_edit).setContentDescription(getString(R.string.VpnSubscriptionRename));
        menu.addItem(MENU_DELETE, R.drawable.msg_delete).setContentDescription(getString(R.string.Delete));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_RENAME) {
                    // the user's own name only: OK on an empty field keeps following the server's title
                    showTextDialog(getString(R.string.VpnSubscriptionRename), sub.name,
                            text -> VpnController.getInstance().renameSubscription(sub, text));
                } else if (id == MENU_DELETE) {
                    confirmDelete();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        RecyclerListView listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        // test results rebind the server rows up to four times a second; no cross-fade for that
        listView.setItemAnimator(null);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position == updateRow) {
                VpnController.getInstance().refreshSubscription(sub, true);
            } else if (position == excludeRow) {
                showTextDialog(getString(R.string.VpnSubscriptionExclude), sub.exclude,
                        text -> VpnController.getInstance().setSubscriptionExclude(sub, text));
            } else if (position == fastestRow) {
                VpnController.getInstance().pickSubscriptionServer(sub, null, "user tapped Fastest server");
            } else if (position >= serversStartRow && position < serversEndRow) {
                VpnKeyInfo server = servers.get(position - serversStartRow);
                if (!sub.isExcluded(server)) {
                    VpnController.getInstance().pickSubscriptionServer(sub, server, "user tapped it");
                }
            }
        });
        ((FrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private interface TextCallback {
        void onText(String text);
    }

    private void showTextDialog(String title, String value, TextCallback callback) {
        if (getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        field.setBackground(null);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setText(value == null ? "" : value);
        field.setSelection(field.getText().length());
        FrameLayout frame = new FrameLayout(context);
        frame.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP, 24, 6, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setView(frame);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.OK), (d, w) -> callback.onText(field.getText().toString()));
        showDialog(builder.create());
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.VpnSubscriptionDeleteTitle));
        builder.setMessage(getString(R.string.VpnSubscriptionDeleteText));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            VpnController.getInstance().deleteSubscription(sub);
            finishFragment();
        });
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void updateRows() {
        servers.clear();
        servers.addAll(sub.servers);
        rowCount = 0;
        updateRow = rowCount++;
        updateInfoRow = rowCount++;
        intervalHeaderRow = rowCount++;
        intervalRow = rowCount++;
        intervalInfoRow = rowCount++;
        excludeRow = rowCount++;
        excludeInfoRow = rowCount++;
        serversHeaderRow = rowCount++;
        fastestRow = rowCount++;
        serversStartRow = rowCount;
        rowCount += servers.size();
        serversEndRow = rowCount;
        serversInfoRow = rowCount++;
    }

    @Override
    public void onVpnListChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            if (!VpnController.getInstance().subscriptions.contains(sub)) {
                finishFragment(); // deleted from the main list
                return;
            }
            if (actionBar != null) {
                actionBar.setTitle(sub.getDisplayName());
            }
            int oldCount = rowCount;
            updateRows();
            if (adapter == null) {
                return;
            }
            if (oldCount != rowCount) {
                adapter.notifyDataSetChanged();
            } else {
                // Every row but the slider: a rebind of the slider row under the finger cancels
                // the drag, and each slider step itself lands here through setSubscriptionInterval.
                adapter.notifyItemRangeChanged(0, intervalRow);
                adapter.notifyItemRangeChanged(intervalRow + 1, rowCount - intervalRow - 1);
            }
        });
    }

    @Override
    public void onVpnStateChanged(int state, String message) {
        onVpnListChanged();
    }

    @Override
    public void onVpnGroupStatusChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            if (adapter != null) {
                adapter.notifyItemRangeChanged(fastestRow, serversEndRow - fastestRow);
            }
        });
    }

    /** The "Fastest server" row: which server that is now, or how to come back to it. */
    private String fastestStatus(int[] colorKey) {
        colorKey[0] = Theme.key_windowBackgroundWhiteGrayText2;
        VpnController c = VpnController.getInstance();
        VpnSubscription.GroupStatus status = sub.groupStatus;
        boolean live = c.isRunning(sub) && c.getState() == SingBoxManager.STATE_CONNECTED && status != null;
        if (live ? status.pinned : sub.pinnedName != null) {
            return getString(R.string.VpnSubscriptionFastestTap);
        }
        if (!c.isRunning(sub) || c.getState() != SingBoxManager.STATE_CONNECTED) {
            return getString(R.string.VpnSubscriptionFastestInfo);
        }
        colorKey[0] = Theme.key_windowBackgroundWhiteBlueText6;
        if (status == null || !status.tested || status.fastestName == null) {
            return getString(R.string.VpnSubscriptionChecking);
        }
        return LocaleController.formatString(R.string.VpnSubscriptionFastestInUse, status.fastestName);
    }

    private String updateInfoText() {
        StringBuilder b = new StringBuilder();
        if (sub.lastUpdateWallMs > 0) {
            b.append(LocaleController.formatString(R.string.VpnSubscriptionLastUpdate,
                    LocaleController.formatDateAudio(sub.lastUpdateWallMs / 1000, true),
                    getString(sub.lastUpdateViaTunnel ? R.string.VpnSubscriptionViaVpn : R.string.VpnSubscriptionDirect)));
        } else {
            b.append(getString(R.string.VpnSubscriptionNeverUpdated));
        }
        if (sub.lastErrorDetail != null) {
            b.append("\n\n").append(LocaleController.formatString(R.string.VpnSubscriptionLastError, sub.lastErrorDetail));
        }
        b.append("\n\n").append(getString(R.string.VpnSubscriptionInfo));
        return b.toString();
    }

    /** Server line under the name, and whether it is the server in use. */
    private String serverStatus(VpnKeyInfo server, int[] colorKey) {
        colorKey[0] = Theme.key_windowBackgroundWhiteGrayText2;
        if (sub.isExcluded(server)) {
            return getString(R.string.VpnSubscriptionExcluded);
        }
        boolean picked = server.name != null && server.name.equals(sub.pinnedName);
        VpnController c = VpnController.getInstance();
        VpnSubscription.GroupStatus status = sub.groupStatus;
        if (!c.isRunning(sub) || c.getState() != SingBoxManager.STATE_CONNECTED || status == null) {
            return picked ? getString(R.string.VpnSubscriptionPicked) : server.getType();
        }
        // While it runs, what the core is actually on (a pick it could not take does not show)
        picked = status.pinned && TextUtils.equals(status.selectedName, server.name);
        if (picked) {
            colorKey[0] = Theme.key_windowBackgroundWhiteBlueText6;
            Integer delay = status.delays.get(server.name);
            return delay != null
                    ? LocaleController.formatString(R.string.VpnSubscriptionPickedDelay, delay)
                    : getString(R.string.VpnSubscriptionPicked);
        }
        if (!status.members.isEmpty() && !status.members.contains(server.name)) {
            colorKey[0] = Theme.key_text_RedRegular;
            return getString(R.string.VpnSubscriptionNotResolved);
        }
        Integer delay = status.delays.get(server.name);
        boolean selected = status.tested && TextUtils.equals(status.selectedName, server.name);
        if (selected) {
            colorKey[0] = Theme.key_windowBackgroundWhiteBlueText6;
            return delay != null
                    ? LocaleController.formatString(R.string.VpnSubscriptionInUseDelay, delay)
                    : getString(R.string.VpnSubscriptionInUse);
        }
        if (delay != null) {
            return LocaleController.formatString(R.string.VpnSubscriptionDelay, delay);
        }
        if (status.tested) {
            colorKey[0] = Theme.key_text_RedRegular;
            return getString(R.string.VpnSubscriptionNoAnswer);
        }
        return getString(R.string.VpnSubscriptionChecking);
    }

    /** Name and status line, like a key row without the gear and the selection box. */
    private static class ServerCell extends FrameLayout {
        final TextView nameView;
        final TextView statusView;

        ServerCell(Context context) {
            super(context);
            nameView = new TextView(context);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 21, 10, 21, 0));
            statusView = new TextView(context);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            statusView.setSingleLine(true);
            statusView.setEllipsize(TextUtils.TruncateAt.END);
            statusView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 21, 35, 21, 0));
            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + 1, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position == updateRow || position == excludeRow || position == fastestRow
                    || (position >= serversStartRow && position < serversEndRow);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == updateRow || position == excludeRow) {
                return VIEW_TYPE_SETTING;
            } else if (position == intervalHeaderRow || position == serversHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == updateInfoRow || position == intervalInfoRow || position == excludeInfoRow
                    || position == serversInfoRow) {
                return VIEW_TYPE_INFO;
            } else if (position == intervalRow) {
                return VIEW_TYPE_SLIDER;
            }
            return VIEW_TYPE_SERVER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case VIEW_TYPE_SLIDER:
                    view = new SlideChooseView(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_SERVER:
                    view = new ServerCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_SETTING:
                default:
                    view = new TextSettingsCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SETTING: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == updateRow) {
                        cell.setTextAndValue(getString(R.string.VpnSubscriptionUpdate),
                                sub.fetching ? getString(R.string.VpnSubscriptionUpdating) : "", false);
                    } else if (position == excludeRow) {
                        cell.setTextAndValue(getString(R.string.VpnSubscriptionExclude),
                                TextUtils.isEmpty(sub.exclude) ? getString(R.string.VpnSubscriptionExcludeNone) : sub.exclude, false);
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == intervalHeaderRow) {
                        cell.setText(getString(R.string.VpnSubscriptionIntervalHeader));
                    } else {
                        cell.setText(LocaleController.formatString(R.string.VpnSubscriptionServersHeader,
                                sub.includedServers().size(), sub.servers.size()));
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == updateInfoRow) {
                        cell.setText(updateInfoText());
                    } else if (position == intervalInfoRow) {
                        cell.setText(getString(R.string.VpnSubscriptionIntervalInfo));
                    } else if (position == serversInfoRow) {
                        cell.setText(getString(R.string.VpnSubscriptionPickInfo));
                    } else {
                        cell.setText(getString(R.string.VpnSubscriptionExcludeInfo));
                    }
                    break;
                }
                case VIEW_TYPE_SLIDER: {
                    SlideChooseView slider = (SlideChooseView) holder.itemView;
                    String[] values = new String[VpnSubscription.CHECK_INTERVALS_SEC.length];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = LocaleController.formatString(R.string.VpnAutoSwitchTimeoutMinutes, VpnSubscription.CHECK_INTERVALS_SEC[i] / 60);
                    }
                    slider.setCallback(i -> VpnController.getInstance().setSubscriptionInterval(sub, i));
                    slider.setOptions(sub.intervalIndex, values);
                    break;
                }
                case VIEW_TYPE_SERVER: {
                    ServerCell cell = (ServerCell) holder.itemView;
                    int[] colorKey = new int[1];
                    if (position == fastestRow) {
                        cell.nameView.setText(getString(R.string.VpnSubscriptionFastest));
                        cell.statusView.setText(fastestStatus(colorKey));
                    } else {
                        VpnKeyInfo server = servers.get(position - serversStartRow);
                        cell.nameView.setText(server.getDisplayName());
                        cell.statusView.setText(serverStatus(server, colorKey));
                    }
                    cell.statusView.setTextColor(Theme.getColor(colorKey[0]));
                    break;
                }
            }
        }
    }
}
