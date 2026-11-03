package cn.local.miaotask;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

public final class SearchInputMethod extends InputMethodService {
    private static SearchInputMethod instance;

    @Override public void onCreate() { super.onCreate(); instance = this; }
    @Override public boolean onEvaluateInputViewShown() { super.onEvaluateInputViewShown(); return false; }

    static boolean commitSearch(String text) {
        BridgeRules.searchText(text);
        SearchInputMethod ime = instance;
        if (ime == null || !ime.getCurrentInputStarted()) return false;
        EditorInfo editor = ime.getCurrentInputEditorInfo();
        InputConnection connection = ime.getCurrentInputConnection();
        if (editor == null || connection == null || !BridgeRules.searchEditor(editor.packageName, editor.inputType)) return false;
        connection.beginBatchEdit();
        try {
            if (!connection.performContextMenuAction(android.R.id.selectAll)
                    && !connection.deleteSurroundingText(100, 100)) return false;
            if (!connection.commitText(text, 1)) return false;
        } finally { connection.endBatchEdit(); }
        connection.performEditorAction(EditorInfo.IME_ACTION_SEARCH);
        return true;
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
