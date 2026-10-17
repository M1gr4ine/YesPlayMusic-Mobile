package dev.yesplaymusic.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure validation for mutations that must stay inside the signed-in user's playlists. */
final class PlaylistEditPolicy {
    private PlaylistEditPolicy() { }

    static boolean isOwnedPlaylist(JSONObject playlist, String accountUserId) {
        if (playlist == null || !NetEaseAuthState.hasPositiveUserId(accountUserId)
                || !NetEaseAuthState.hasPositiveUserId(playlist.optString("id", ""))
                || playlist.optBoolean("subscribed", false) || playlist.optInt("specialType", 0) == 5) {
            return false;
        }
        JSONObject creator = playlist.optJSONObject("creator");
        String ownerId = creator == null ? "" : creator.optString("userId", "");
        return NetEaseAuthState.hasPositiveUserId(ownerId)
                && accountUserId.trim().equals(ownerId.trim());
    }

    static List<String> requirePositiveIds(JSONArray values, int limit) {
        if (values == null || values.length() == 0 || limit <= 0 || values.length() > limit) {
            throw new IllegalArgumentException("请提供有效的歌曲 ID 列表，最多 " + limit + " 首");
        }
        List<String> ids = new ArrayList<>(values.length());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            String id = value instanceof String ? ((String) value).trim() : "";
            if (!NetEaseAuthState.hasPositiveUserId(id)) {
                throw new IllegalArgumentException("歌曲 ID 必须为正整数");
            }
            if (!seen.add(id)) throw new IllegalArgumentException("歌曲 ID 不能重复");
            ids.add(id);
        }
        return ids;
    }

    static List<String> completeTrackIds(JSONObject playlist, int limit) {
        if (playlist == null || limit <= 0) throw new IllegalArgumentException("无法读取歌单详情");
        int trackCount = playlist.optInt("trackCount", -1);
        JSONArray values = playlist.optJSONArray("trackIds");
        if (trackCount < 0 || trackCount > limit || values == null || values.length() != trackCount) {
            throw new IllegalArgumentException("歌单曲目列表不完整或超过编辑上限");
        }
        List<String> ids = new ArrayList<>(values.length());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject track = values.optJSONObject(i);
            String id = track == null ? "" : track.optString("id", "").trim();
            if (!NetEaseAuthState.hasPositiveUserId(id)) {
                throw new IllegalArgumentException("歌单中包含无效的歌曲 ID");
            }
            if (!seen.add(id)) throw new IllegalArgumentException("歌单中包含重复歌曲 ID");
            ids.add(id);
        }
        return ids;
    }

    static boolean sameTrackIds(List<String> current, List<String> desired) {
        if (current == null || desired == null || current.size() != desired.size()) return false;
        Set<String> currentSet = new HashSet<>(current);
        Set<String> desiredSet = new HashSet<>(desired);
        return currentSet.size() == current.size() && desiredSet.size() == desired.size()
                && currentSet.equals(desiredSet);
    }

    static boolean removalMatches(List<String> before, List<String> removed, List<String> after) {
        if (before == null || removed == null || after == null) return false;
        Set<String> beforeSet = new HashSet<>(before);
        Set<String> removedSet = new HashSet<>(removed);
        if (beforeSet.size() != before.size() || removedSet.size() != removed.size()
                || !beforeSet.containsAll(removedSet)) return false;
        List<String> expected = new ArrayList<>(before.size() - removed.size());
        for (String id : before) if (!removedSet.contains(id)) expected.add(id);
        return sameTrackIds(expected, after);
    }
}
