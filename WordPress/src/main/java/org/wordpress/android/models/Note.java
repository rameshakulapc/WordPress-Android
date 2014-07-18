/**
 * Note represents a single WordPress.com notification
 */
package org.wordpress.android.models;

import android.text.Spannable;
import android.text.TextUtils;
import android.util.Log;

import com.simperium.client.BucketSchema;
import com.simperium.client.Syncable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.datasets.CommentTable;
import org.wordpress.android.ui.notifications.NotificationUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.DateTimeUtils;
import org.wordpress.android.util.JSONUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Note extends Syncable {
    private static final String TAG = "NoteModel";

    // Maximum character length for a comment preview
    static private final int MAX_COMMENT_PREVIEW_LENGTH = 200;

    private static final String NOTE_UNKNOWN_TYPE = "unknown";
    private static final String NOTE_COMMENT_TYPE = "comment";
    private static final String NOTE_MATCHER_TYPE = "automattcher";

    // JSON action keys
    private static final String ACTION_KEY_REPLY = "replyto-comment";
    private static final String ACTION_KEY_APPROVE = "approve-comment";
    private static final String ACTION_KEY_UNAPPROVE = "unapprove-comment";
    private static final String ACTION_KEY_SPAM = "spam-comment";

    public static enum EnabledActions {
        ACTION_REPLY,
        ACTION_APPROVE,
        ACTION_UNAPPROVE,
        ACTION_SPAM
    }

    private JSONObject mActions;
    private JSONObject mNoteJSON;

    private long mTimestamp;

    private transient String mCommentPreview;
    private Spannable mSubject;
    private transient String mIconUrl;
    private transient String mNoteType;

    /**
     * Create a note using JSON from Simperium
     */
    public Note(JSONObject noteJSON) {
        mNoteJSON = noteJSON;
    }

    /**
     * Simperium method @see Diffable
     */
    @Override
    public JSONObject getDiffableValue() {
        return mNoteJSON;
    }

    /**
     * Simperium method for identifying bucket object @see Diffable
     */
    @Override
    public String getSimperiumKey() {
        return getId();
    }

    public JSONObject toJSONObject() {
        return mNoteJSON;
    }

    public String getId() {
        return String.valueOf(queryJSON("id", 0));
    }

    public String getType() {
        if (mNoteType == null) {
            mNoteType = queryJSON("type", NOTE_UNKNOWN_TYPE);
        }

        return mNoteType;
    }

    private Boolean isType(String type) {
        return getType().equals(type);
    }

    public Boolean isCommentType() {
        return (isAutomattcherType() && JSONUtil.queryJSON(mNoteJSON, "meta.ids.comment", -1) != -1) ||
                isType(NOTE_COMMENT_TYPE);
    }

    public Boolean isAutomattcherType() {
        return isType(NOTE_MATCHER_TYPE);
    }

    public Spannable getSubject() {
        if (mSubject == null) {
            try {
                JSONObject subject = mNoteJSON.getJSONObject("subject");
                mSubject = NotificationUtils.getSpannableTextFromIndices(subject, null);
            } catch (JSONException e) {
                e.printStackTrace();
            }

        }
        return mSubject;
    }

    public String getIconURL() {
        if (mIconUrl==null)
            mIconUrl = queryJSON("icon", "");

        return mIconUrl;
    }

    /**
     * Searches for 'comment' type in the note body and returns the 'text' value
     */
    public String getCommentPreview() {
        if (mCommentPreview == null) {
            JSONArray noteBodyItems = getBody();
            if (noteBodyItems != null) {
                for (int i=0; i < noteBodyItems.length(); i++) {
                    JSONObject bodyItem = noteBodyItems.optJSONObject(i);
                    if (bodyItem != null && bodyItem.optString("type", "").equals(NOTE_COMMENT_TYPE)) {
                        mCommentPreview = bodyItem.optString("text", "");
                        break;
                    }
                }
            }

            // Trim down the comment preview if the comment text is too large.
            if (mCommentPreview != null && mCommentPreview.length() > MAX_COMMENT_PREVIEW_LENGTH) {
                mCommentPreview = mCommentPreview.substring(0, MAX_COMMENT_PREVIEW_LENGTH - 1);
            }
        }

        return mCommentPreview;
    }

    /**
     * The inverse of isRead
     */
    public Boolean isUnread() {
        return !isRead();
    }


    Boolean isRead() {
        return queryJSON("read", 0) == 1;
    }

    public void markAsRead() {
        try {
            mNoteJSON.put("read", 1);
        } catch (JSONException e) {
            Log.e(TAG, "Unable to update note read property", e);
            return;
        }
        save();
    }


    public Reply buildReply(String content) {
        String restPath;
        if (this.isCommentType()) {
            restPath = String.format("sites/%d/comments/%d", getBlogId(), getCommentId());
        } else {
            restPath = String.format("sites/%d/posts/%d", getBlogId(), getPostId());
        }

        return new Reply(this, String.format("%s/replies/new", restPath), content);
    }

    /**
     * Get the timestamp provided by the API for the note - cached for performance
     */
    public long getTimestamp() {
        if (mTimestamp == 0) {
            mTimestamp = DateTimeUtils.iso8601ToTimestamp(queryJSON("timestamp", ""));
        }

        return mTimestamp;
    }

    public JSONArray getBody() {
        try {
            return mNoteJSON.getJSONArray("body");
        } catch (JSONException e) {
            return null;
        }
    }

    // returns character code for notification font
    public String getNoticonCharacter() {
        return queryJSON("noticon", "");
    }

    JSONObject getCommentActions() {
        if (mActions == null) {
            mActions = queryJSON("body[last].actions", new JSONObject());
        }

        return mActions;
    }


    private void updateJSON(JSONObject json) {

        mNoteJSON = json;

        // clear out the preloaded content
        mTimestamp = 0;
        mCommentPreview = null;
        mSubject = null;
        mIconUrl = null;
        mNoteType = null;
    }

    /*
     * returns the actions allowed on this note, assumes it's a comment notification
     */
    public EnumSet<EnabledActions> getEnabledActions() {
        EnumSet<EnabledActions> actions = EnumSet.noneOf(EnabledActions.class);
        JSONObject jsonActions = getCommentActions();
        if (jsonActions == null || jsonActions.length() == 0)
            return actions;
        if (jsonActions.has(ACTION_KEY_REPLY))
            actions.add(EnabledActions.ACTION_REPLY);
        if (jsonActions.has(ACTION_KEY_APPROVE) && jsonActions.optBoolean(ACTION_KEY_APPROVE, false))
            actions.add(EnabledActions.ACTION_UNAPPROVE);
        if (jsonActions.has(ACTION_KEY_APPROVE) && !jsonActions.optBoolean(ACTION_KEY_APPROVE, false))
            actions.add(EnabledActions.ACTION_APPROVE);
        if (jsonActions.has(ACTION_KEY_SPAM))
            actions.add(EnabledActions.ACTION_SPAM);
        return actions;
    }

    public int getBlogId() {
        return JSONUtil.queryJSON(mNoteJSON, "meta.ids.site", 0);
    }

    public int getPostId() {
        return JSONUtil.queryJSON(mNoteJSON, "meta.ids.post", 0);
    }

    public long getCommentId() {
        return JSONUtil.queryJSON(mNoteJSON, "meta.ids.comment", 0);
    }

    /**
     * Rudimentary system for pulling an item out of a JSON object hierarchy
     */
    public <U> U queryJSON(String query, U defaultObject) {
        return JSONUtil.queryJSON(this.toJSONObject(), query, defaultObject);
    }

    /**
     * Constructs a new Comment object based off of data in a Note
     */
    public Comment buildComment() {
        return new Comment(
                queryJSON("meta.ids.post", -1),
                queryJSON("meta.ids.comment", -1),
                getCommentAuthorName(),
                DateTimeUtils.timestampToIso8601Str(getTimestamp()),
                getCommentText(),
                getCommentStatus(),
                "", // post title is unavailable in note model
                getCommentAuthorUrl(),
                "", // user email is unavailable in note model
                getIconURL()
        );
    }

    private String getCommentAuthorName() {
        JSONArray bodyArray = getBody();

        for (int i=0; i < bodyArray.length(); i++) {
            try {
                JSONObject bodyItem = bodyArray.getJSONObject(i);
                if (bodyItem.has("type") && bodyItem.optString("type").equals("user")) {
                    return bodyItem.optString("text");
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        return "";
    }

    private String getCommentText() {
        return queryJSON("body[last].text", "");
    }

    private String getCommentAuthorUrl() {
        JSONArray bodyArray = getBody();

        for (int i=0; i < bodyArray.length(); i++) {
            try {
                JSONObject bodyItem = bodyArray.getJSONObject(i);
                if (bodyItem.has("type") && bodyItem.optString("type").equals("user")) {
                    return JSONUtil.queryJSON(bodyItem, "meta.links.home", "");
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        return "";
    }

    private String getCommentStatus() {
        EnumSet<EnabledActions> enabledActions = getEnabledActions();

        if (enabledActions.contains(EnabledActions.ACTION_UNAPPROVE)) {
            return CommentStatus.toString(CommentStatus.APPROVED);
        } else if (enabledActions.contains(EnabledActions.ACTION_SPAM)) {
            return CommentStatus.toString(CommentStatus.SPAM);
        } else {
            return CommentStatus.toString(CommentStatus.UNAPPROVED);
        }

    }

    /**
     * Represents a user replying to a note.
     */
    public static class Reply {
        private final Note mNote;
        private final String mContent;
        private final String mRestPath;

        Reply(Note note, String restPath, String content) {
            mNote = note;
            mRestPath = restPath;
            mContent = content;
        }

        public String getContent() {
            return mContent;
        }

        public String getRestPath() {
            return mRestPath;
        }
    }

    public static class Schema extends BucketSchema<Note> {

        static public final String NAME = "note20";
        static public final String TIMESTAMP_INDEX = "timestamp";

        private static final Indexer<Note> sTimestampIndexer = new Indexer<Note>() {

            @Override
            public List<Index> index(Note note) {
                List<Index> indexes = new ArrayList<Index>(1);
                try {
                    indexes.add(new Index(TIMESTAMP_INDEX, note.getTimestamp()));
                } catch (NumberFormatException e) {
                    // note will not have an indexed timestamp so it will
                    // show up at the end of a query sorting by timestamp
                    android.util.Log.e("WordPress", "Failed to index timestamp", e);
                }
                return indexes;
            }

        };

        public Schema() {
            // save an index with a timestamp
            addIndex(sTimestampIndexer);
        }

        @Override
        public String getRemoteName() {
            return NAME;
        }

        @Override
        public Note build(String key, JSONObject properties) {
            return new Note(properties);
        }

        public void update(Note note, JSONObject properties) {
            note.updateJSON(properties);
        }

    }
}