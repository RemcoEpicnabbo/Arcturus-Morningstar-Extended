package com.eu.habbo.habbohotel.mentions;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.messenger.MessengerBuddy;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomChatType;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.habbohotel.users.HabboManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MentionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MentionManager.class);

    private static final int ROOM_NAME_MAX_LENGTH = 64;
    private static final int MESSAGE_MAX_LENGTH = 255;

    private final ConcurrentHashMap<Integer, Long> cooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> roomBroadcastCooldowns = new ConcurrentHashMap<>();

    // Per-user request rate limits for the incoming packets that hit the DB.
    private final ConcurrentHashMap<Integer, Long> requestListCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> markReadCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> markAllCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> deleteCooldowns = new ConcurrentHashMap<>();

    private volatile long lastPrune = System.currentTimeMillis();
    private static final long PRUNE_INTERVAL_MS = 5 * 60_000L;

    public boolean isEnabled() {
        return Emulator.getConfig().getInt("mentions.enabled", 1) == 1;
    }

    /** Broadcast category resolved from a mention alias. */
    public enum BroadcastScope {
        NONE,
        // @room / @stanza - reaches the people currently in the room.
        ROOM,
        // @friends / @amici - reaches the sender's online friends, requires acc_mention_friends.
        FRIENDS,
        // @all / @everyone / @tutti - reaches every online user, requires acc_mention_everyone.
        EVERYONE
    }

    /** Permission key (DB column) required to send an "everyone" broadcast. */
    public static final String PERMISSION_EVERYONE = "acc_mention_everyone";
    /** Permission key (DB column) required to send a "friends" broadcast. */
    public static final String PERMISSION_FRIENDS = "acc_mention_friends";

    private Set<String> parseAliases(String configKey, String defaultValue) {
        Set<String> aliases = new HashSet<>();
        String raw = Emulator.getConfig().getValue(configKey, defaultValue);
        for (String alias : raw.split(",")) {
            String trimmed = alias.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                aliases.add(trimmed);
            }
        }
        return aliases;
    }

    private Set<String> roomAliases() {
        return parseAliases("mentions.room.aliases", "room,stanza");
    }

    private Set<String> friendsAliases() {
        return parseAliases("mentions.friends.aliases", "friends,amici");
    }

    private Set<String> everyoneAliases() {
        return parseAliases("mentions.everyone.aliases", "all,everyone,tutti");
    }

    /**
     * Classify an alias candidate (lowercased, punctuation-trimmed) into a
     * broadcast scope. {@link BroadcastScope#EVERYONE} wins over
     * {@link BroadcastScope#FRIENDS} which wins over {@link BroadcastScope#ROOM}
     * so an admin who's also configured the same word into two lists gets the
     * most permissive scope (which is also the one requiring the strongest
     * permission, so it can't be misused).
     */
    private BroadcastScope classifyAlias(String alias,
                                         Set<String> everyone,
                                         Set<String> friends,
                                         Set<String> room) {
        if (alias.isEmpty()) return BroadcastScope.NONE;
        if (everyone.contains(alias)) return BroadcastScope.EVERYONE;
        if (friends.contains(alias)) return BroadcastScope.FRIENDS;
        if (room.contains(alias)) return BroadcastScope.ROOM;
        return BroadcastScope.NONE;
    }

    public void process(Habbo sender, Room room, String message, RoomChatType type) {
        try {
            if (!this.isEnabled()) {
                return;
            }

            if (sender == null || room == null || message == null) {
                return;
            }

            if (message.isEmpty() || message.indexOf('@') < 0) {
                return;
            }

            int senderId = sender.getHabboInfo().getId();
            long now = System.currentTimeMillis();
            long cooldownMs = Emulator.getConfig().getInt("mentions.cooldown.ms", 3000);
            Long last = this.cooldowns.get(senderId);
            if (last != null && (now - last) < cooldownMs) {
                return;
            }

            Set<String> roomAliases = this.roomAliases();
            Set<String> friendsAliases = this.friendsAliases();
            Set<String> everyoneAliases = this.everyoneAliases();

            BroadcastScope broadcastScope = BroadcastScope.NONE;
            LinkedHashSet<String> directTokens = new LinkedHashSet<>();

            for (String token : message.split("\\s+")) {
                if (token.length() < 2 || token.charAt(0) != '@') {
                    continue;
                }

                String raw = token.substring(1);
                String aliasCandidate = trimTrailingPunctuation(raw).toLowerCase();

                BroadcastScope scope = this.classifyAlias(aliasCandidate, everyoneAliases, friendsAliases, roomAliases);

                if (scope != BroadcastScope.NONE) {
                    // Promote to the strongest detected scope so a message with
                    // both @room and @all routes through the @all permission.
                    if (scope.ordinal() > broadcastScope.ordinal()) {
                        broadcastScope = scope;
                    }
                } else if (!raw.isEmpty()) {
                    directTokens.add(raw);
                }
            }

            // Gate the broadcast on the matching permission. If the sender does
            // not have the right to use it, drop the broadcast entirely but
            // keep processing any direct @nick tokens in the same message.
            if (broadcastScope == BroadcastScope.EVERYONE && !sender.hasPermission(PERMISSION_EVERYONE)) {
                broadcastScope = BroadcastScope.NONE;
            } else if (broadcastScope == BroadcastScope.FRIENDS && !sender.hasPermission(PERMISSION_FRIENDS)) {
                broadcastScope = BroadcastScope.NONE;
            }

            if (broadcastScope == BroadcastScope.NONE && directTokens.isEmpty()) {
                return;
            }

            // Stricter cooldown for broadcasts: one @all/@friends/@room expands to
            // up to mentions.max.targets DB writes and packet sends, so rate-limit it
            // separately from direct mentions.
            if (broadcastScope != BroadcastScope.NONE) {
                long roomCooldownMs = Emulator.getConfig().getInt("mentions.room.cooldown.ms", 15000);
                Long lastRoom = this.roomBroadcastCooldowns.get(senderId);
                if (lastRoom != null && (now - lastRoom) < roomCooldownMs) {
                    return;
                }
            }

            int maxTargets = Emulator.getConfig().getInt("mentions.max.targets", 50);
            if (maxTargets <= 0) maxTargets = 1;

            // Bound the number of direct tokens we even attempt to resolve so a
            // crafted message can't push us through the room iteration N times.
            int maxDirectTokens = Math.min(directTokens.size(), maxTargets);

            List<Habbo> targets = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();

            switch (broadcastScope) {
                case EVERYONE:
                    this.collectEveryoneTargets(senderId, targets, seen, maxTargets);
                    break;
                case FRIENDS:
                    this.collectFriendsTargets(sender, senderId, targets, seen, maxTargets);
                    break;
                case ROOM:
                    this.collectRoomTargets(room, senderId, targets, seen, maxTargets);
                    break;
                case NONE:
                default:
                    int processed = 0;
                    for (String token : directTokens) {
                        if (processed++ >= maxDirectTokens) break;
                        Habbo habbo = this.resolveHabbo(room, token);
                        if (habbo == null || habbo.getHabboInfo().getId() == senderId) {
                            continue;
                        }
                        if (seen.add(habbo.getHabboInfo().getId())) {
                            targets.add(habbo);
                        }
                        if (targets.size() >= maxTargets) {
                            break;
                        }
                    }
                    break;
            }

            if (targets.isEmpty()) {
                return;
            }

            this.cooldowns.put(senderId, now);
            if (broadcastScope != BroadcastScope.NONE) this.roomBroadcastCooldowns.put(senderId, now);
            this.pruneCooldownsIfDue(now);

            int mentionType = (broadcastScope != BroadcastScope.NONE) ? HabboMention.TYPE_ROOM : HabboMention.TYPE_DIRECT;
            int timestamp = Emulator.getIntUnixTimestamp();
            String roomName = truncate(room.getName(), ROOM_NAME_MAX_LENGTH);
            String storedMessage = truncate(message, MESSAGE_MAX_LENGTH);

            for (Habbo target : targets) {
                this.store(target, sender, room, storedMessage, mentionType, timestamp, roomName);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to process mentions.", e);
        }
    }

    private void collectRoomTargets(Room room, int senderId, List<Habbo> targets, Set<Integer> seen, int maxTargets) {
        for (Habbo habbo : room.getHabbos()) {
            if (habbo == null || habbo.getHabboInfo().getId() == senderId) continue;
            if (seen.add(habbo.getHabboInfo().getId())) targets.add(habbo);
            if (targets.size() >= maxTargets) break;
        }
    }

    private void collectFriendsTargets(Habbo sender, int senderId, List<Habbo> targets, Set<Integer> seen, int maxTargets) {
        if (sender.getMessenger() == null) return;
        HabboManager habboManager = Emulator.getGameEnvironment().getHabboManager();
        for (MessengerBuddy buddy : sender.getMessenger().getFriends().values()) {
            if (buddy == null) continue;
            int buddyId = buddy.getId();
            if (buddyId == senderId) continue;
            Habbo online = habboManager.getHabbo(buddyId);
            if (online == null) continue;
            if (seen.add(buddyId)) targets.add(online);
            if (targets.size() >= maxTargets) break;
        }
    }

    private void collectEveryoneTargets(int senderId, List<Habbo> targets, Set<Integer> seen, int maxTargets) {
        for (Habbo habbo : Emulator.getGameEnvironment().getHabboManager().getOnlineHabbos().values()) {
            if (habbo == null || habbo.getHabboInfo().getId() == senderId) continue;
            if (seen.add(habbo.getHabboInfo().getId())) targets.add(habbo);
            if (targets.size() >= maxTargets) break;
        }
    }

    private void store(Habbo target, Habbo sender, Room room, String message, int mentionType, int timestamp, String roomName) {
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO habbo_mentions (target_user_id, sender_user_id, sender_username, room_id, room_name, message, mention_type, timestamp, `read`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, target.getHabboInfo().getId());
            statement.setInt(2, sender.getHabboInfo().getId());
            statement.setString(3, sender.getHabboInfo().getUsername());
            statement.setInt(4, room.getId());
            statement.setString(5, roomName);
            statement.setString(6, message);
            statement.setInt(7, mentionType);
            statement.setInt(8, timestamp);
            statement.executeUpdate();

            int generatedId = 0;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getInt(1);
                }
            }

            // Don't push a notification to the client when the INSERT did not
            // return an id - the client dedup keys on id and a 0 would skip
            // dedup entirely, opening a flood path on the next packet.
            if (generatedId <= 0) {
                return;
            }

            HabboMention mention = new HabboMention(target.getHabboInfo().getId(), generatedId, sender, room, roomName, message, mentionType, timestamp);

            if (target.getClient() != null) {
                target.getClient().sendResponse(new com.eu.habbo.messages.outgoing.mentions.MentionReceivedComposer(mention));
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to store mention.", e);
        }
    }

    public List<HabboMention> getMentions(int userId, int limit) {
        List<HabboMention> mentions = new ArrayList<>();
        if (limit <= 0) limit = 50;
        if (limit > 200) limit = 200;
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM habbo_mentions WHERE target_user_id = ? ORDER BY id DESC LIMIT ?")) {
            statement.setInt(1, userId);
            statement.setInt(2, limit);
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    mentions.add(new HabboMention(set));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to load mentions.", e);
        }
        return mentions;
    }

    public void markRead(int userId, int mode, int mentionId) {
        // Caller has already validated mode and mentionId; this method is defensive only.
        if (mode != 0 && mode != 1) return;
        if (mode == 1 && mentionId <= 0) return;

        String query = mode == 1
                ? "UPDATE habbo_mentions SET `read` = 1 WHERE target_user_id = ? AND id = ? AND `read` = 0"
                : "UPDATE habbo_mentions SET `read` = 1 WHERE target_user_id = ? AND `read` = 0";
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setInt(1, userId);
            if (mode == 1) {
                statement.setInt(2, mentionId);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to mark mentions as read.", e);
        }
    }

    public void delete(int userId, int mentionId) {
        if (mentionId <= 0) return;
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM habbo_mentions WHERE target_user_id = ? AND id = ?")) {
            statement.setInt(1, userId);
            statement.setInt(2, mentionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to delete mention.", e);
        }
    }

    /**
     * Per-user rate limit for {@code RequestMentionsEvent}. Returns true when
     * the caller should be served, false when it must be silently dropped.
     */
    public boolean tryAcquireRequestList(int userId) {
        long cooldownMs = Emulator.getConfig().getInt("mentions.request.cooldown.ms", 2000);
        return tryAcquire(this.requestListCooldowns, userId, cooldownMs);
    }

    /**
     * Per-user rate limit for {@code MarkMentionsReadEvent}. The mark-single
     * path (mode == 1) is cheap and gets a short window; the mark-all path
     * (mode != 1) is a bulk UPDATE and gets a longer one.
     */
    public boolean tryAcquireMarkRead(int userId, int mode) {
        long cooldownMs;
        ConcurrentHashMap<Integer, Long> bucket;
        if (mode == 1) {
            cooldownMs = Emulator.getConfig().getInt("mentions.markread.cooldown.ms", 500);
            bucket = this.markReadCooldowns;
        } else {
            cooldownMs = Emulator.getConfig().getInt("mentions.markall.cooldown.ms", 5000);
            bucket = this.markAllCooldowns;
        }
        return tryAcquire(bucket, userId, cooldownMs);
    }

    /**
     * Per-user rate limit for {@code DeleteMentionEvent}.
     */
    public boolean tryAcquireDelete(int userId) {
        long cooldownMs = Emulator.getConfig().getInt("mentions.delete.cooldown.ms", 500);
        return tryAcquire(this.deleteCooldowns, userId, cooldownMs);
    }

    private boolean tryAcquire(ConcurrentHashMap<Integer, Long> bucket, int userId, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = bucket.get(userId);
        if (last != null && (now - last) < cooldownMs) {
            return false;
        }
        bucket.put(userId, now);
        this.pruneCooldownsIfDue(now);
        return true;
    }

    /**
     * Periodically drop cooldown entries older than the largest window so the
     * maps don't accumulate one entry per user-that-ever-played for the entire
     * server lifetime.
     */
    private void pruneCooldownsIfDue(long now) {
        if (now - this.lastPrune < PRUNE_INTERVAL_MS) return;
        this.lastPrune = now;

        long mentionWindow = Emulator.getConfig().getInt("mentions.cooldown.ms", 3000);
        long roomWindow = Emulator.getConfig().getInt("mentions.room.cooldown.ms", 15000);
        long requestWindow = Emulator.getConfig().getInt("mentions.request.cooldown.ms", 2000);
        long markReadWindow = Emulator.getConfig().getInt("mentions.markread.cooldown.ms", 500);
        long markAllWindow = Emulator.getConfig().getInt("mentions.markall.cooldown.ms", 5000);
        long deleteWindow = Emulator.getConfig().getInt("mentions.delete.cooldown.ms", 500);

        prune(this.cooldowns, now, mentionWindow);
        prune(this.roomBroadcastCooldowns, now, roomWindow);
        prune(this.requestListCooldowns, now, requestWindow);
        prune(this.markReadCooldowns, now, markReadWindow);
        prune(this.markAllCooldowns, now, markAllWindow);
        prune(this.deleteCooldowns, now, deleteWindow);
    }

    private static void prune(ConcurrentHashMap<Integer, Long> bucket, long now, long windowMs) {
        Iterator<Map.Entry<Integer, Long>> it = bucket.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> entry = it.next();
            Long value = entry.getValue();
            if (value == null || (now - value) >= windowMs) {
                it.remove();
            }
        }
    }

    private static final String TRAILING_PUNCTUATION = ".,!?;:)]}\"'";

    private static String trimTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }

    /**
     * Resolve a present room occupant from a raw mention token. Tries the token
     * verbatim first (so usernames containing allowed punctuation such as '-',
     * '.', '!' still match), then falls back to a trailing-punctuation-trimmed
     * form so a mention written as "@Bob!" still resolves the user "Bob".
     */
    private Habbo resolveHabbo(Room room, String rawToken) {
        Habbo habbo = room.getHabbo(rawToken);
        if (habbo != null) {
            return habbo;
        }
        String trimmed = trimTrailingPunctuation(rawToken);
        if (!trimmed.isEmpty() && !trimmed.equals(rawToken)) {
            return room.getHabbo(trimmed);
        }
        return null;
    }
}
