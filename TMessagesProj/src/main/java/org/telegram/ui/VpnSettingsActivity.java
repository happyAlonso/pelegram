package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.vpn.SingBoxConfigBuilder;
import org.telegram.messenger.vpn.VpnController;
import org.telegram.messenger.vpn.VpnKeyInfo;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class VpnSettingsActivity extends BaseFragment {

    private static final int done_button = 1;
    private static final int scan_button = 2;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 3;

    // AmneziaVPN QR codes carry a chunk header and may split a config across several codes; collect
    // them here until every part is scanned.
    private final HashMap<Integer, byte[]> qrChunks = new HashMap<>();
    private int qrChunkCount = 0;

    private static final int KIND_STRING = 0;
    private static final int KIND_NUMBER = 1;
    private static final int KIND_BOOL = 2;
    private static final int KIND_ARRAY = 3;

    private final VpnKeyInfo editingInfo;

    private EditTextBoldCursor nameField;
    private EditTextBoldCursor rawKeyField;          // add mode / non-parseable edit
    private final List<EditField> editFields = new ArrayList<>();  // parsed edit mode
    private JSONObject editOutbound;                 // parsed config being edited

    private static final Map<String, String> LABELS = new HashMap<>();
    static {
        LABELS.put("server", "Address");
        LABELS.put("server_port", "Port");
        LABELS.put("peers.0.address", "Server address");
        LABELS.put("peers.0.port", "Server port");
        LABELS.put("peers.0.public_key", "Server public key");
        LABELS.put("peers.0.pre_shared_key", "Preshared key");
        LABELS.put("peers.0.allowed_ips", "Allowed IPs");
        LABELS.put("peers.0.persistent_keepalive_interval", "Keepalive (s)");
        LABELS.put("private_key", "Private key");
        LABELS.put("public_key", "Public key");
        LABELS.put("pre_shared_key", "Preshared key");
        LABELS.put("address", "Local address");
        LABELS.put("uuid", "UUID");
        LABELS.put("password", "Password");
        LABELS.put("mtu", "MTU");
        LABELS.put("type", "Protocol");
        LABELS.put("tls.server_name", "SNI");
        LABELS.put("tls.reality.public_key", "REALITY public key");
        LABELS.put("tls.reality.short_id", "REALITY short id");
        LABELS.put("obfs.password", "Obfs password");
    }

    private static class EditField {
        final String path;
        final int kind;
        EditTextBoldCursor editText;
        EditField(String path, int kind) {
            this.path = path;
            this.kind = kind;
        }
    }

    public VpnSettingsActivity() {
        editingInfo = null;
    }

    public VpnSettingsActivity(VpnKeyInfo info) {
        editingInfo = info;
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(getString(editingInfo != null ? R.string.VpnDetails : R.string.AddVpn));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    save();
                } else if (id == scan_button) {
                    openQrScanner();
                }
            }
        });
        // In add mode, offer scanning a key from a QR code.
        if (editingInfo == null) {
            ActionBarMenuItem scanItem = actionBar.createMenu().addItemWithWidth(scan_button, R.drawable.msg_qr_mini, AndroidUtilities.dp(56));
            scanItem.setContentDescription(getString(R.string.VpnScanQr));
        }
        ActionBarMenuItem doneItem = actionBar.createMenu().addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        doneItem.setContentDescription(getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        AndroidUtilities.setScrollViewEdgeEffectColor(scrollView, Theme.getColor(Theme.key_actionBarDefault));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        linearLayout.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Try to open an existing connection as editable parsed fields.
        JSONObject parsed = null;
        if (editingInfo != null) {
            try {
                parsed = SingBoxConfigBuilder.buildOutbound(editingInfo.key);
            } catch (Exception ignored) {
            }
        }

        nameField = createField(context, getString(R.string.VpnNameHint));
        container.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 21, 4, 21, 4));

        String infoText;
        if (parsed != null) {
            // edit mode: one field per config setting
            editOutbound = parsed;
            List<EditField> fields = new ArrayList<>();
            flatten(parsed, "", fields);
            for (EditField f : fields) {
                Object value = valueAtPath(parsed, f.path);
                f.editText = createField(context, labelFor(f.path));
                f.editText.setText(value == null ? "" : value.toString());
                container.addView(f.editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 21, 4, 21, 4));
                editFields.add(f);
            }
            infoText = getString(R.string.VpnDetailsInfo);
        } else {
            // add mode (or unreadable key): a single field holding the whole key string
            rawKeyField = createField(context, getString(R.string.VpnKeyHint));
            if (editingInfo != null) {
                rawKeyField.setText(editingInfo.key);
            }
            // insert above the name field
            container.addView(rawKeyField, 0, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 21, 4, 21, 4));
            infoText = getString(R.string.VpnAddInfo);
        }

        if (editingInfo != null) {
            nameField.setText(editingInfo.name);
        }

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
        info.setText(infoText);
        linearLayout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return fragmentView;
    }

    private void openQrScanner() {
        if (getParentActivity() == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && getParentActivity().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            getParentActivity().requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
            return;
        }
        qrChunks.clear();
        qrChunkCount = 0;
        final BottomSheet[] sheet = new BottomSheet[1];
        sheet[0] = CameraScanActivity.showAsSheet(this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public boolean isContinuousScan() {
                return true; // keep the camera open so split AmneziaVPN codes can be scanned in a row
            }

            @Override
            public void didFindQr(String text) {
                AndroidUtilities.runOnUIThread(() -> onQrScanned(text, sheet[0]));
            }
        });
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (getParentActivity() == null) {
            return;
        }
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openQrScanner();
            } else {
                new AlertDialog.Builder(getParentActivity())
                        .setMessage(AndroidUtilities.replaceTags(getString(R.string.QRCodePermissionNoCameraWithHint)))
                        .setPositiveButton(getString(R.string.PermissionOpenSettings), (dialog, which) -> {
                            try {
                                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(android.net.Uri.parse("package:" + getParentActivity().getPackageName()));
                                getParentActivity().startActivity(intent);
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                        })
                        .setNegativeButton(getString(R.string.ContactsPermissionAlertNotNow), null)
                        .show();
            }
        }
    }

    // Handle a scanned QR: a plain key (ss://, vless://, ...) goes straight into the field; an
    // AmneziaVPN chunk is collected (and, if split, the scanner reopens) until every part is in,
    // then the reassembled config is filled in.
    private void onQrScanned(String text, BottomSheet sheet) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        int[] parts = SingBoxConfigBuilder.amneziaQrParts(text);
        if (parts == null) {
            // plain key (ss://, vless://, vpn://, ...)
            qrChunks.clear();
            setRawKey(text.trim());
            dismissSheet(sheet);
            return;
        }
        int count = parts[0], index = parts[1];
        if (count <= 0) {
            return;
        }
        if (qrChunkCount != count) {
            qrChunks.clear();
            qrChunkCount = count;
        }
        if (qrChunks.containsKey(index)) {
            return; // already have this part; the camera stays open for the remaining ones
        }
        byte[] data = SingBoxConfigBuilder.amneziaQrChunkData(text);
        if (data == null) {
            return;
        }
        qrChunks.put(index, data);
        if (qrChunks.size() < count) {
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), LocaleController.formatString(R.string.VpnQrPart, qrChunks.size(), count), Toast.LENGTH_SHORT).show();
            }
            return; // keep the camera open for the remaining parts
        }
        String key = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (int i = 0; i < count; i++) {
                byte[] d = qrChunks.get(i);
                if (d != null) {
                    bos.write(d);
                }
            }
            key = SingBoxConfigBuilder.amneziaQrToKey(bos.toByteArray());
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            qrChunks.clear();
            qrChunkCount = 0;
        }
        if (key != null) {
            setRawKey(key);
        }
        dismissSheet(sheet);
    }

    private void dismissSheet(BottomSheet sheet) {
        if (sheet != null) {
            try {
                sheet.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    private void setRawKey(String key) {
        if (rawKeyField != null && !TextUtils.isEmpty(key)) {
            rawKeyField.setText(key);
            rawKeyField.setSelection(rawKeyField.getText().length());
        }
    }

    private EditTextBoldCursor createField(Context context, String hint) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setBackground(null);
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        field.setTransformHintToHeader(true);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular));
        field.setHintText(hint);
        field.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(0, 0, 0, 0);
        field.setOnEditorActionListener((tv, i, e) -> {
            if (i == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });
        return field;
    }

    private void save() {
        if (getParentActivity() == null) {
            return;
        }
        String name = nameField.getText().toString().trim();
        if (editOutbound != null) {
            // edit mode: write field values back into the parsed config
            try {
                for (EditField f : editFields) {
                    applyValue(editOutbound, f.path, f.editText.getText().toString(), f.kind);
                }
                String json = editOutbound.toString();
                SingBoxConfigBuilder.buildOutbound(json); // validate
                editingInfo.key = json;
                editingInfo.name = name;
                VpnController.getInstance().updateVpn(editingInfo);
                finishFragment();
            } catch (Exception e) {
                Toast.makeText(getParentActivity(), getString(R.string.VpnInvalidKey), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // add mode (or raw-key edit)
        String key = rawKeyField.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(getParentActivity(), getString(R.string.VpnInvalidKey), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SingBoxConfigBuilder.buildOutbound(key);
        } catch (Exception e) {
            Toast.makeText(getParentActivity(), getString(R.string.VpnInvalidKey), Toast.LENGTH_SHORT).show();
            return;
        }
        if (editingInfo != null) {
            editingInfo.key = key;
            editingInfo.name = name;
            VpnController.getInstance().updateVpn(editingInfo);
        } else {
            VpnController.getInstance().addVpn(new VpnKeyInfo(name, key));
        }
        finishFragment();
    }

    // ---- config <-> editable fields ----

    private void flatten(Object node, String path, List<EditField> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                if ("tag".equals(k)) {
                    continue;
                }
                flatten(o.opt(k), path.isEmpty() ? k : path + "." + k, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            boolean allPrimitive = true;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    allPrimitive = false;
                    break;
                }
            }
            if (allPrimitive) {
                out.add(new EditField(path, KIND_ARRAY));
            } else {
                for (int i = 0; i < a.length(); i++) {
                    flatten(a.opt(i), path + "." + i, out);
                }
            }
        } else if (node != null && node != JSONObject.NULL) {
            int kind = node instanceof Boolean ? KIND_BOOL : (node instanceof Number ? KIND_NUMBER : KIND_STRING);
            out.add(new EditField(path, kind));
        }
    }

    private Object valueAtPath(JSONObject root, String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (cur instanceof JSONArray) {
                cur = ((JSONArray) cur).opt(Integer.parseInt(part));
            } else if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).opt(part);
            } else {
                return null;
            }
        }
        if (cur instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray a = (JSONArray) cur;
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(a.opt(i));
            }
            return sb.toString();
        }
        return cur;
    }

    private void applyValue(JSONObject root, String path, String text, int kind) throws Exception {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (cur instanceof JSONArray) {
                cur = ((JSONArray) cur).get(Integer.parseInt(parts[i]));
            } else {
                cur = ((JSONObject) cur).get(parts[i]);
            }
        }
        String last = parts[parts.length - 1];
        Object value;
        String t = text.trim();
        switch (kind) {
            case KIND_NUMBER:
                try {
                    value = Long.parseLong(t);
                } catch (NumberFormatException e) {
                    value = Double.parseDouble(t);
                }
                break;
            case KIND_BOOL:
                value = Boolean.parseBoolean(t);
                break;
            case KIND_ARRAY:
                JSONArray arr = new JSONArray();
                for (String s : text.split(",")) {
                    String v = s.trim();
                    if (!v.isEmpty()) {
                        arr.put(v);
                    }
                }
                value = arr;
                break;
            default:
                value = text;
                break;
        }
        if (cur instanceof JSONArray) {
            ((JSONArray) cur).put(Integer.parseInt(last), value);
        } else {
            ((JSONObject) cur).put(last, value);
        }
    }

    private String labelFor(String path) {
        String mapped = LABELS.get(path);
        if (mapped != null) {
            return mapped;
        }
        // prettify: last meaningful segment, underscores -> spaces, capitalized
        String[] parts = path.split("\\.");
        String last = parts[parts.length - 1];
        if (last.matches("\\d+") && parts.length >= 2) {
            last = parts[parts.length - 2];
        }
        last = last.replace('_', ' ').trim();
        if (last.isEmpty()) {
            return path;
        }
        return Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }
}
