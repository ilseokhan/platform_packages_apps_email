/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.database.MergeCursor;
import android.graphics.Typeface;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.security.InvalidParameterException;

/**
 * The adapter for displaying mailboxes.
 *
 * Do not use {@link #getItemId(int)} -- It's only for ListView.  Use {@link #getMailboxId}
 * instead.  (See the comment below)
 *
 * TODO New UI will probably not distinguish unread counts from # of messages.
 *      i.e. we won't need two different viewes for them.
 * TODO Show "Starred" per account?  (Right now we have only "All Starred")
 * TODO Unit test, when UI is settled.
 */
/* package */ class MailboxesAdapter extends CursorAdapter {
    public static final int MODE_NORMAL = 0;
    public static final int MODE_MOVE_TO_TARGET = 1;

    /*
     * Note here we have two ID columns.  The first one is for ListView, which doesn't like ID
     * values to be negative.  The second one is the actual mailbox ID, which we use in the rest
     * of code.
     * ListView uses row IDs for some operations, including onSave/RestoreInstanceState,
     * and if we use negative IDs they don't work as expected.
     * Because ListView finds the ID column by name ("_id"), we rename the second column
     * so that ListView gets the correct column.
     */
    /* package */ static final String[] PROJECTION = new String[] { MailboxColumns.ID,
            MailboxColumns.ID + " AS org_mailbox_id",
            MailboxColumns.DISPLAY_NAME, MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
            MailboxColumns.MESSAGE_COUNT};
    // Column 0 is only for ListView; we don't use it in our code.
    private static final int COLUMN_ID = 1;
    private static final int COLUMN_DISPLAY_NAME = 2;
    private static final int COLUMN_TYPE = 3;
    private static final int COLUMN_UNREAD_COUNT = 4;
    private static final int COLUMN_MESSAGE_COUNT = 5;

    private static final String MAILBOX_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?" +
            " AND " + MailboxColumns.TYPE + "<" + Mailbox.TYPE_NOT_EMAIL +
            " AND " + MailboxColumns.FLAG_VISIBLE + "=1";

    private static final String MAILBOX_SELECTION_MOVE_TO_FOLDER =
            MAILBOX_SELECTION + " AND " + Mailbox.MOVE_TO_TARGET_MAILBOX_SELECTION;

    private static final String MAILBOX_ORDER_BY = "CASE " + MailboxColumns.TYPE +
            " WHEN " + Mailbox.TYPE_INBOX   + " THEN 0" +
            " WHEN " + Mailbox.TYPE_DRAFTS  + " THEN 1" +
            " WHEN " + Mailbox.TYPE_SENT    + " THEN 2" +
            " WHEN " + Mailbox.TYPE_OUTBOX  + " THEN 3" +
            " WHEN " + Mailbox.TYPE_TRASH   + " THEN 20" + // After standard mailboxes
            " WHEN " + Mailbox.TYPE_JUNK    + " THEN 21" + // After standard mailboxes
            " ELSE 10 END" + // for Mailbox.TYPE_MAIL, standard mailboxes
            " ," + MailboxColumns.DISPLAY_NAME;

    private final LayoutInflater mInflater;

    private final int mMode;

    public MailboxesAdapter(Context context, int mode) {
        super(context, null, 0 /* no auto-requery */);
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mMode = mode;
    }

    public long getMailboxId(int position) {
        Cursor c = (Cursor) getItem(position);
        return c.getLong(COLUMN_ID);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        switch (mMode) {
            case MODE_NORMAL:
                bindViewNormalMode(view, context, cursor);
                return;
            case MODE_MOVE_TO_TARGET:
                bindViewMoveToTargetMode(view, context, cursor);
                return;
        }
        throw new IllegalStateException();
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        switch (mMode) {
            case MODE_NORMAL:
                return newViewNormalMode(context, cursor, parent);
            case MODE_MOVE_TO_TARGET:
                return newViewMoveToTargetMode(context, cursor, parent);
        }
        throw new IllegalStateException();
    }

    private static String getMailboxName(Context context, Cursor cursor) {
        final int type = cursor.getInt(COLUMN_TYPE);
        final long mailboxId = cursor.getLong(COLUMN_ID);
        String mailboxName = Utility.FolderProperties.getInstance(context)
                .getDisplayName(type, mailboxId);
        if (mailboxName == null) {
            mailboxName = cursor.getString(COLUMN_DISPLAY_NAME);
        }
        return mailboxName;
    }

    private void bindViewNormalMode(View view, Context context, Cursor cursor) {
        final int type = cursor.getInt(COLUMN_TYPE);
        final long mailboxId = cursor.getLong(COLUMN_ID);

        // Set mailbox name
        final TextView nameView = (TextView) view.findViewById(R.id.mailbox_name);
        nameView.setText(getMailboxName(context, cursor));

        // Set count
        boolean useTotalCount = false;
        switch (type) {
            case Mailbox.TYPE_DRAFTS:
            case Mailbox.TYPE_OUTBOX:
            case Mailbox.TYPE_SENT:
            case Mailbox.TYPE_TRASH:
                useTotalCount = true;
                break;
        }
        final int count = cursor.getInt(useTotalCount ? COLUMN_MESSAGE_COUNT : COLUMN_UNREAD_COUNT);
        final TextView unreadCountView = (TextView) view.findViewById(R.id.new_message_count);
        final TextView allCountView = (TextView) view.findViewById(R.id.all_message_count);

        // If the unread count is zero, not to show countView.
        if (count > 0) {
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            if (useTotalCount) {
                unreadCountView.setVisibility(View.GONE);
                allCountView.setVisibility(View.VISIBLE);
                allCountView.setText(Integer.toString(count));
            } else {
                allCountView.setVisibility(View.GONE);
                unreadCountView.setVisibility(View.VISIBLE);
                unreadCountView.setText(Integer.toString(count));
            }
        } else {
            nameView.setTypeface(Typeface.DEFAULT);
            allCountView.setVisibility(View.GONE);
            unreadCountView.setVisibility(View.GONE);
        }

        // Set folder icon
        ((ImageView) view.findViewById(R.id.folder_icon))
                .setImageDrawable(Utility.FolderProperties.getInstance(context)
                .getIcon(type, mailboxId));
    }

    private View newViewNormalMode(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(R.layout.mailbox_list_item, parent, false);
    }

    private void bindViewMoveToTargetMode(View view, Context context, Cursor cursor) {
        TextView t = (TextView) view;
        t.setText(getMailboxName(context, cursor));
    }

    private View newViewMoveToTargetMode(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(android.R.layout.simple_list_item_1, parent, false);
    }

    /**
     * @return mailboxes Loader for an account.
     */
    public static Loader<Cursor> createLoader(Context context, long accountId,
            int mode) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxesAdapter createLoader accountId=" + accountId);
        }
        return new MailboxesLoader(context, accountId, mode);
    }

    /**
     * Loader for mailboxes.  If there's more than 1 account set up, the result will also include
     * special mailboxes.  (e.g. combined inbox, etc)
     */
    private static class MailboxesLoader extends ThrottlingCursorLoader {
        private final Context mContext;
        private final int mMode;

        private static String getSelection(int mode) {
            if (mode == MODE_MOVE_TO_TARGET) {
                return MAILBOX_SELECTION_MOVE_TO_FOLDER;
            } else {
                return MAILBOX_SELECTION;
            }
        }

        public MailboxesLoader(Context context, long accountId, int mode) {
            super(context, EmailContent.Mailbox.CONTENT_URI,
                    MailboxesAdapter.PROJECTION, getSelection(mode),
                    new String[] { String.valueOf(accountId) }, MAILBOX_ORDER_BY);
            mContext = context;
            mMode = mode;
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor mailboxes = super.loadInBackground();
            if (mMode == MODE_MOVE_TO_TARGET) {
                return mailboxes;
            }
            if (mailboxes.getCount() == 0) {
                // If there's no mailboxes, don't merge special mailboxes.  Just return 0 row
                // cursor.
                // If there's no row, this means the account has just been set up or recovered and
                // we're fetching mailboxes.  In this case, the mailbox list shouldn't just show
                // special mailboxes.  It should show something to indicate it's still loading the
                // list, which MailboxListFragment will do if it returns an empty cursor.
                return mailboxes;
            }

            final int numAccounts = EmailContent.count(mContext, Account.CONTENT_URI);
            return new MergeCursor(
                    new Cursor[] {getSpecialMailboxesCursor(mContext, numAccounts > 1), mailboxes});
        }
    }

    /* package */ static Cursor getSpecialMailboxesCursor(Context context, boolean mShowCombined) {
        MatrixCursor cursor = new MatrixCursor(PROJECTION);
        if (mShowCombined) {
            // Combined inbox -- show unread count
            addSummaryMailboxRow(context, cursor,
                    Mailbox.QUERY_ALL_INBOXES, Mailbox.TYPE_INBOX,
                    Mailbox.getUnreadCountByMailboxType(context, Mailbox.TYPE_INBOX), true);
        }

        // Favorite (starred) -- show # of favorites
        addSummaryMailboxRow(context, cursor,
                Mailbox.QUERY_ALL_FAVORITES, Mailbox.TYPE_MAIL,
                Message.getFavoriteMessageCount(context), false);

        if (mShowCombined) {
            // Drafts -- show # of drafts
            addSummaryMailboxRow(context, cursor,
                    Mailbox.QUERY_ALL_DRAFTS, Mailbox.TYPE_DRAFTS,
                    Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_DRAFTS), false);

            // Outbox -- # of sent messages
            addSummaryMailboxRow(context, cursor,
                    Mailbox.QUERY_ALL_OUTBOX, Mailbox.TYPE_OUTBOX,
                    Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_OUTBOX), false);
        }

        return cursor;
    }

    private static void addSummaryMailboxRow(Context context, MatrixCursor cursor,
            long id, int type, int count, boolean showAlways) {
        if (id >= 0) {
            throw new InvalidParameterException(); // Must be QUERY_ALL_*, which are all negative.
        }
        if (showAlways || (count > 0)) {
            RowBuilder row = cursor.newRow();
            row.add(Long.MAX_VALUE + id); // Map QUERY_ALL_* constants to positive ints.
            row.add(id); // The real mailbox ID.
            row.add(""); // Display name.  We get it from FolderProperties.
            row.add(type);
            row.add(count);
            row.add(count);
        }
    }

    /* package */ static long getIdForTest(Cursor cursor) {
        return cursor.getLong(COLUMN_ID);
    }

    /* package */ static int getTypeForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_TYPE);
    }

    /* package */ static int getMessageCountForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_MESSAGE_COUNT);
    }

    /* package */ static int getUnreadCountForTest(Cursor cursor) {
        return cursor.getInt(COLUMN_UNREAD_COUNT);
    }
}