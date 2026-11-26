package dev.yesplaymusic.probe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Match-only alternate artist names and structured per-member credits taken from official provider metadata. */
final class ArtistAliases {
    static final int MAX_MEMBER_COUNT = 16;
    private static final int MAX_ALIAS_COUNT = 32;
    private static final int MAX_ALIAS_LENGTH = 128;
    private static final int MAX_SEARCH_RETRIES = 2;
    private static final int MAX_MEMBER_SEARCH_RETRIES = 3;
    private static final String[] ALIAS_FIELDS = {"alias", "alia", "tns", "trans", "transNames"};
    private static final Pattern COLLABORATION =
            Pattern.compile("\\b(feat\\.?|ft\\.?|featuring|with|vs\\.?)\\b|\\s+x\\s+");
    private static final Pattern EXPLICIT_JOIN_SEPARATOR =
            Pattern.compile("\\s+/\\s+|[\\\\、;；\\r\\n]");

    private ArtistAliases() {}

    /** Ordered artist credit member with optional provider-scoped artist ID and member-scoped official aliases. */
    static final class Member {
        final String name;
        final String providerId;
        final List<String> aliases;

        Member(String name, String providerId, List<String> aliases) {
            this.name = clean(name);
            this.providerId = cleanProviderId(providerId);
            this.aliases = cleanMemberAliases(this.name, aliases);
        }

        Member withAddedAliases(List<String> extraAliases) {
            if (extraAliases == null || extraAliases.isEmpty() || name.isEmpty()) return this;
            List<String> combined = new ArrayList<>(aliases.size() + extraAliases.size());
            combined.addAll(aliases);
            combined.addAll(extraAliases);
            List<String> merged = cleanMemberAliases(name, combined);
            return merged.equals(aliases) ? this : new Member(name, providerId, merged);
        }
    }

    private static final class ResolvedCredit {
        final List<Set<String>> visibleParts;
        final List<Set<String>> expandedParts;
        final boolean[] hasOfficialAliases;

        ResolvedCredit(List<Set<String>> visibleParts, List<Set<String>> expandedParts, boolean[] hasOfficialAliases) {
            this.visibleParts = visibleParts;
            this.expandedParts = expandedParts;
            this.hasOfficialAliases = hasOfficialAliases;
        }

        boolean hasLeadOfficialAliases() {
            return hasOfficialAliases.length > 0 && hasOfficialAliases[0];
        }
    }

    static int score(String title, String artist, List<String> artistAliases, long duration,
                     String candidateTitle, String candidateArtist, List<String> candidateAliases,
                     long candidateDuration) {
        return score(title, artist, Collections.emptyList(), artistAliases, "", duration,
                candidateTitle, candidateArtist, Collections.emptyList(), candidateAliases, "", candidateDuration);
    }

    static int score(String title, String artist, List<String> artistAliases, String album, long duration,
                     String candidateTitle, String candidateArtist, List<String> candidateAliases,
                     String candidateAlbum, long candidateDuration) {
        return score(title, artist, Collections.emptyList(), artistAliases, album, duration,
                candidateTitle, candidateArtist, Collections.emptyList(), candidateAliases, candidateAlbum, candidateDuration);
    }

    static int score(String title, String artist, List<Member> artistMembers, List<String> artistAliases,
                     String album, long duration,
                     String candidateTitle, String candidateArtist, List<Member> candidateMembers,
                     List<String> candidateAliases, String candidateAlbum, long candidateDuration) {
        if (!QQMatch.knownArtist(artist) || !QQMatch.knownArtist(candidateArtist)) return -1;
        ResolvedCredit left = resolveCredit(artist, artistMembers, artistAliases);
        ResolvedCredit right = resolveCredit(candidateArtist, candidateMembers, candidateAliases);
        if (left == null || right == null || left.visibleParts.size() != right.visibleParts.size()) return -1;
        if (!bijectionMatchesWithoutConflict(left, right)) return -1;
        return QQMatch.scoreParts(title, left.expandedParts, artist, album, duration,
                candidateTitle, right.expandedParts, candidateArtist, candidateAlbum, candidateDuration);
    }

    /**
     * Bidirectional album bridge backed strictly by official artist identity evidence.
     * Accepts only when one side is a single artist and the other is a collaboration whose lead artist
     * officially matches that solo credit, on the same non-compilation album, same recording title/version,
     * and valid durations within 5 seconds.
     */
    static boolean evidencedLeadAlbumBridge(
            String leftTitle, String leftArtist, List<Member> leftMembers, List<String> leftAliases,
            String leftAlbum, long leftDuration,
            String rightTitle, String rightArtist, List<Member> rightMembers, List<String> rightAliases,
            String rightAlbum, long rightDuration) {
        if (!QQMatch.sameRecordingTitle(leftTitle, rightTitle) || leftDuration <= 0 || rightDuration <= 0
                || Math.abs(leftDuration - rightDuration) > QQMatch.UNM_DURATION_MS) return false;
        if (!QQMatch.knownArtist(leftArtist) || !QQMatch.knownArtist(rightArtist)) return false;
        if (!QQMatch.knownArtist(leftAlbum) || !QQMatch.knownArtist(rightAlbum)
                || !QQMatch.sameRecordingTitle(leftAlbum, rightAlbum)
                || isGenericCompilation(leftAlbum) || isGenericCompilation(rightAlbum)) return false;

        ResolvedCredit left = resolveCredit(leftArtist, leftMembers, leftAliases);
        ResolvedCredit right = resolveCredit(rightArtist, rightMembers, rightAliases);
        if (left == null || right == null) return false;

        int leftSize = left.visibleParts.size();
        int rightSize = right.visibleParts.size();
        if (!((leftSize == 1 && rightSize >= 2) || (rightSize == 1 && leftSize >= 2))) return false;

        ResolvedCredit solo = leftSize == 1 ? left : right;
        ResolvedCredit collab = leftSize == 1 ? right : left;
        return pairMatchesWithoutConflict(
                solo.visibleParts.get(0), solo.expandedParts.get(0),
                collab.visibleParts.get(0), collab.expandedParts.get(0));
    }

    /** Context-dependent forward playback fallback. Never call this as an unranked title/duration match. */
    static boolean bridgeAccepts(String title, String artist, List<String> artistAliases, String album, long duration,
                                 String candidateTitle, String candidateArtist, List<String> candidateAliases,
                                 String candidateAlbum, long candidateDuration, boolean artistQuery, int resultRank) {
        return bridgeAccepts(title, artist, Collections.emptyList(), artistAliases, album, duration,
                candidateTitle, candidateArtist, Collections.emptyList(), candidateAliases, candidateAlbum,
                candidateDuration, artistQuery, resultRank);
    }

    static boolean bridgeAccepts(String title, String artist, List<Member> artistMembers, List<String> artistAliases,
                                 String album, long duration,
                                 String candidateTitle, String candidateArtist, List<Member> candidateMembers,
                                 List<String> candidateAliases, String candidateAlbum, long candidateDuration,
                                 boolean artistQuery, int resultRank) {
        if (!QQMatch.sameRecordingTitle(title, candidateTitle) || duration <= 0 || candidateDuration <= 0
                || Math.abs(duration - candidateDuration) > QQMatch.UNM_DURATION_MS) return false;
        if (!QQMatch.knownArtist(artist) || !QQMatch.knownArtist(candidateArtist)) return false;

        ResolvedCredit candidate = resolveCredit(candidateArtist, candidateMembers, candidateAliases);
        if (candidate == null || candidate.visibleParts.size() != 1) return false;
        if ((candidateMembers == null || candidateMembers.isEmpty()) && !isSingleArtistCredit(candidateArtist)) {
            return false;
        }

        if (evidencedLeadAlbumBridge(title, artist, artistMembers, artistAliases, album, duration,
                candidateTitle, candidateArtist, candidateMembers, candidateAliases, candidateAlbum, candidateDuration)) {
            return true;
        }

        ResolvedCredit target = resolveCredit(artist, artistMembers, artistAliases);
        if (target == null) return false;

        Set<String> leadVisible = target.visibleParts.get(0);
        Set<String> leadExpanded = target.expandedParts.get(0);
        Set<String> candVisible = candidate.visibleParts.get(0);
        Set<String> candExpanded = candidate.expandedParts.get(0);

        // A candidate that matches only a non-lead guest member must never be bridged as the lead artist.
        boolean matchesLeadDirectly = !Collections.disjoint(leadExpanded, candExpanded);
        if (!matchesLeadDirectly && matchesOnlyGuest(target, candExpanded)) return false;

        if (matchesLeadDirectly
                && (hasSameScriptConflict(candVisible, leadExpanded)
                        || hasSameScriptConflict(leadVisible, candExpanded))) {
            return false;
        }

        boolean albumMatch = QQMatch.knownArtist(album) && QQMatch.knownArtist(candidateAlbum)
                && QQMatch.sameRecordingTitle(album, candidateAlbum);
        boolean single = target.visibleParts.size() == 1
                && ((artistMembers != null && artistMembers.size() == 1) || isSingleArtistCredit(artist));
        boolean leadHasKnownAliases = target.hasLeadOfficialAliases()
                || ((artistMembers == null || artistMembers.isEmpty()) && hasUsableAlias(artistAliases));

        // If official aliases already cover the candidate's script without matching, reject as a known identity conflict.
        if (leadHasKnownAliases && !matchesLeadDirectly) {
            if (scriptOverlapWithKnown(leadExpanded, artistAliases, target.visibleParts.size() == 1, candVisible)) {
                return false;
            }
        }

        boolean crossScript = single && crossScript(artist, candidateArtist);
        if (albumMatch) {
            if (matchesLeadDirectly && !isGenericCompilation(album) && !isGenericCompilation(candidateAlbum)) {
                return true;
            }
            if (crossScript) return true;
            if (!leadHasKnownAliases && distinctiveAlbum(album) && bilingualCredit(candidateArtist)) {
                return true;
            }
        }
        return artistQuery && resultRank == 0 && crossScript;
    }

    private static boolean matchesOnlyGuest(ResolvedCredit target, Set<String> candidateExpanded) {
        for (int i = 1; i < target.expandedParts.size(); i++) {
            if (!Collections.disjoint(target.expandedParts.get(i), candidateExpanded)) return true;
        }
        return false;
    }

    private static boolean scriptOverlapWithKnown(Set<String> leadExpanded, List<String> flatAliases,
                                                  boolean singleTarget, Set<String> candidateVisible) {
        int knownScripts = 0;
        for (String token : leadExpanded) knownScripts |= scripts(token);
        if (!singleTarget && flatAliases != null) {
            for (String alias : flatAliases) {
                String cleanAlias = clean(alias);
                if (!cleanAlias.isEmpty() && isSingleArtistCredit(cleanAlias)) knownScripts |= scripts(cleanAlias);
            }
        }
        int candScripts = 0;
        for (String token : candidateVisible) candScripts |= scripts(token);
        return (knownScripts & candScripts) != 0;
    }

    private static boolean hasUsableAlias(List<String> aliases) {
        if (aliases == null) return false;
        for (String alias : aliases) {
            String cleanAlias = clean(alias);
            if (!cleanAlias.isEmpty() && isSingleArtistCredit(cleanAlias)) return true;
        }
        return false;
    }

    private static boolean isGenericCompilation(String album) {
        String value = QQMatch.normalize(album);
        return value.contains("greatesthits") || value.contains("bestof") || value.contains("collection")
                || value.contains("compilation") || value.contains("精选") || value.contains("合辑");
    }

    private static boolean distinctiveAlbum(String album) {
        String value = QQMatch.normalize(album);
        return value.length() >= 8 && !isGenericCompilation(album);
    }

    private static boolean bilingualCredit(String credit) {
        Set<String> names = QQMatch.artistNames(credit);
        for (String a : names) for (String b : names) if (crossScript(a, b)) return true;
        return false;
    }

    private static boolean bijectionMatchesWithoutConflict(ResolvedCredit left, ResolvedCredit right) {
        int size = left.expandedParts.size();
        if (size == 0 || size != right.expandedParts.size()) return false;
        boolean[] used = new boolean[size];
        for (int i = 0; i < size; i++) {
            int match = -1;
            for (int j = 0; j < size; j++) {
                if (!used[j] && !Collections.disjoint(left.expandedParts.get(i), right.expandedParts.get(j))) {
                    if (match >= 0) return false; // ambiguous alias attribution
                    match = j;
                }
            }
            if (match < 0) return false;
            if (!pairMatchesWithoutConflict(
                    left.visibleParts.get(i), left.expandedParts.get(i),
                    right.visibleParts.get(match), right.expandedParts.get(match))) {
                return false;
            }
            used[match] = true;
        }
        return true;
    }

    private static boolean pairMatchesWithoutConflict(Set<String> leftVisible, Set<String> leftExpanded,
                                                      Set<String> rightVisible, Set<String> rightExpanded) {
        if (Collections.disjoint(leftExpanded, rightExpanded)) return false;
        return !hasSameScriptConflict(rightVisible, leftExpanded)
                && !hasSameScriptConflict(leftVisible, rightExpanded);
    }

    private static boolean hasSameScriptConflict(Set<String> visibleNames, Set<String> expectedFromOther) {
        for (String known : visibleNames) {
            if (!expectedFromOther.contains(known)) continue;
            int knownScripts = scripts(known);
            if (knownScripts == 0) continue;
            for (String other : visibleNames) {
                if (!expectedFromOther.contains(other) && (knownScripts & scripts(other)) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ResolvedCredit resolveCredit(String artist, List<Member> members, List<String> flatAliases) {
        List<Set<String>> visibleParts = QQMatch.artistParts(artist);
        if (visibleParts.isEmpty()) return null;

        // 1. Authoritative complete structured member array aligned with the visible artist display string:
        // preserve official member boundaries so internal punctuation like "A & B", "AC/DC", or
        // "Tyler, The Creator" stays a single artist while conflicting display groupings like "A / B"
        // with [Member("A & B")] are rejected.
        if (members != null && !members.isEmpty() && isCompleteConsistentMembers(artist, members)) {
            List<Set<String>> atomVisible = new ArrayList<>(members.size());
            List<Set<String>> atomExpanded = new ArrayList<>(members.size());
            boolean[] hasOfficial = new boolean[members.size()];
            for (int i = 0; i < members.size(); i++) {
                Member member = members.get(i);
                Set<String> visible = QQMatch.memberAtomNames(member.name);
                Set<String> expanded = new LinkedHashSet<>(visible);
                for (String alias : member.aliases) {
                    for (String token : QQMatch.memberAtomNames(alias)) {
                        if (expanded.add(token)) hasOfficial[i] = true;
                    }
                }
                atomVisible.add(visible);
                atomExpanded.add(expanded);
            }
            if (members.size() == 1 && flatAliases != null) {
                Set<String> targetSet = atomExpanded.get(0);
                for (String alias : flatAliases) {
                    String cleanAlias = clean(alias);
                    if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
                    for (String token : QQMatch.memberAtomNames(cleanAlias)) {
                        if (targetSet.add(token)) hasOfficial[0] = true;
                    }
                }
            }
            return new ResolvedCredit(atomVisible, atomExpanded, hasOfficial);
        }

        List<Set<String>> expanded = new ArrayList<>(visibleParts.size());
        for (Set<String> part : visibleParts) expanded.add(new LinkedHashSet<>(part));
        boolean[] hasOfficial = new boolean[visibleParts.size()];

        // 2. Partial structured members (e.g. only lead artist recovered): preserve every visible artist
        // and attach aliases only when every supplied member uniquely matches a visible part.
        if (members != null && !members.isEmpty()) {
            int[] mapped = mapConsistentMembers(visibleParts, members);
            if (mapped != null) {
                for (int i = 0; i < members.size(); i++) {
                    int visibleIndex = mapped[i];
                    if (visibleIndex < 0) continue;
                    Member member = members.get(i);
                    Set<String> targetSet = expanded.get(visibleIndex);
                    for (String alias : member.aliases) {
                        for (String token : QQMatch.memberAtomNames(alias)) {
                            if (targetSet.add(token)) hasOfficial[visibleIndex] = true;
                        }
                    }
                }
            }
        }

        // 3. Residual flat aliases apply strictly to single-artist credits only.
        if (visibleParts.size() == 1 && isSingleArtistCredit(artist) && flatAliases != null) {
            Set<String> targetSet = expanded.get(0);
            for (String alias : flatAliases) {
                String cleanAlias = clean(alias);
                if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
                for (String token : QQMatch.memberAtomNames(cleanAlias)) {
                    if (targetSet.add(token)) hasOfficial[0] = true;
                }
            }
        }

        return new ResolvedCredit(visibleParts, expanded, hasOfficial);
    }

    /**
     * Verifies that the structured member list aligns verbatim (after NFKC/case/CJK folding) with the
     * visible artist display string and its explicit groupings. Preserves single artists containing
     * internal punctuation (e.g. "A & B", "AC/DC", "Tyler, The Creator") while rejecting contradictory
     * groupings such as display "A / B" paired with [Member("A & B")].
     */
    private static boolean isCompleteConsistentMembers(String artist, List<Member> members) {
        List<String> memberNames = new ArrayList<>(members.size());
        for (Member member : members) {
            if (member == null || member.name.isEmpty() || containsExplicitJoinSeparator(member.name)) {
                return false;
            }
            if (QQMatch.memberAtomNames(member.name).isEmpty()) return false;
            memberNames.add(member.name);
        }
        return QQMatch.matchesDisplayCredit(artist, memberNames);
    }

    private static boolean containsExplicitJoinSeparator(String value) {
        return value != null && EXPLICIT_JOIN_SEPARATOR.matcher(value).find();
    }

    /**
     * Maps partial or reordered single-part structured members onto visible artist parts. Returns null if any
     * member contradicts the visible credit, ensuring corrupted metadata can never overwrite visible artists.
     */
    private static int[] mapConsistentMembers(List<Set<String>> visibleParts, List<Member> members) {
        int[] mapped = new int[members.size()];
        boolean[] used = new boolean[visibleParts.size()];
        for (int i = 0; i < members.size(); i++) {
            Member member = members.get(i);
            if (member == null || member.name.isEmpty() || containsExplicitJoinSeparator(member.name)) return null;
            List<Set<String>> memberParts = QQMatch.artistParts(member.name);
            if (memberParts.size() != 1) return null;
            Set<String> canonical = memberParts.get(0);
            int found = -1;
            for (int j = 0; j < visibleParts.size(); j++) {
                if (!used[j] && visibleParts.get(j).equals(canonical)) {
                    found = j;
                    break;
                }
            }
            if (found < 0) return null;
            used[found] = true;
            mapped[i] = found;
        }
        return mapped;
    }

    private static boolean crossScript(String left, String right) {
        int a = scripts(left), b = scripts(right);
        return a != 0 && b != 0 && (a & b) == 0;
    }

    private static int scripts(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) == '\u0000') return 0;
        int mask = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) mask |= 1;
            else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) mask |= 2;
            else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA) mask |= 4;
            else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL) mask |= 8;
        }
        // Kana distinguishes Japanese from a Chinese rendering even where both contain Han.
        return (mask & 4) != 0 ? (mask & ~2) : mask;
    }

    static List<String> searchQueries(String title, String canonicalArtist, List<String> aliases) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanArtist = canonicalArtist == null ? "" : canonicalArtist.trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 200 || !isSingleArtistCredit(cleanArtist)) {
            return Collections.emptyList();
        }
        String normalizedCanonical = QQMatch.normalizeArtistToken(cleanArtist);
        if (normalizedCanonical.isEmpty() || aliases == null || aliases.isEmpty()) return Collections.emptyList();

        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String alias : aliases) {
            String cleanAlias = clean(alias);
            if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
            String normalizedAlias = QQMatch.normalizeArtistToken(cleanAlias);
            if (normalizedAlias.isEmpty() || normalizedAlias.equals(normalizedCanonical)
                    || unique.containsKey(normalizedAlias)) continue;
            unique.put(normalizedAlias, cleanAlias);
            if (unique.size() >= MAX_SEARCH_RETRIES) break;
        }

        if (unique.isEmpty()) return Collections.emptyList();
        List<String> queries = new ArrayList<>(unique.size());
        for (String alias : unique.values()) queries.add(cleanTitle + " " + alias);
        return Collections.unmodifiableList(queries);
    }

    /** Generates a small bounded set of deduplicated recall queries using benign-cleaned metadata and member aliases. */
    static List<String> searchQueries(String title, String canonicalArtist,
                                      List<Member> members, List<String> flatAliases) {
        String rawTitle = title == null ? "" : title.trim();
        String rawArtist = canonicalArtist == null ? "" : canonicalArtist.trim();
        if (rawTitle.isEmpty() || rawTitle.length() > 200 || rawArtist.isEmpty()) {
            return Collections.emptyList();
        }

        String primary = QQMatch.keyword(rawTitle, rawArtist);
        String cleanTitle = QQMatch.cleanSearchTitle(rawTitle);
        String cleanArtist = QQMatch.cleanSearchArtist(rawArtist);
        LinkedHashMap<String, String> queries = new LinkedHashMap<>();
        String primaryKey = queryKey(primary);

        // 1. Cleaned primary query (strips only recognized benign title markers and bracketed artist aliases).
        String cleanedPrimary = QQMatch.keyword(cleanTitle, cleanArtist);
        addQuery(queries, primaryKey, cleanedPrimary, MAX_MEMBER_SEARCH_RETRIES);

        List<Set<String>> visibleParts = QQMatch.artistParts(rawArtist);
        if (visibleParts.isEmpty()) return immutableValues(queries);

        boolean completeStructured = members != null && !members.isEmpty()
                && isCompleteConsistentMembers(rawArtist, members);

        // 2. Single-artist credit (authoritative 1-member credit or legacy single-artist string).
        if ((completeStructured && members.size() == 1)
                || (visibleParts.size() == 1 && isSingleArtistCredit(rawArtist))) {
            List<String> combined = new ArrayList<>();
            if (completeStructured) {
                combined.addAll(members.get(0).aliases);
            } else if (members != null && members.size() == 1) {
                int[] mapped = mapConsistentMembers(visibleParts, members);
                if (mapped != null && mapped[0] == 0) combined.addAll(members.get(0).aliases);
            }
            if (flatAliases != null) combined.addAll(flatAliases);
            String canonicalToken = QQMatch.normalizeArtistToken(cleanArtist);
            int addedAliases = 0;
            for (String alias : combined) {
                if (addedAliases >= MAX_SEARCH_RETRIES || queries.size() >= MAX_MEMBER_SEARCH_RETRIES) break;
                String cleanAlias = clean(alias);
                if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
                String aliasToken = QQMatch.normalizeArtistToken(cleanAlias);
                if (aliasToken.isEmpty() || aliasToken.equals(canonicalToken)) continue;
                int before = queries.size();
                addQuery(queries, primaryKey, cleanTitle + " " + cleanAlias, MAX_MEMBER_SEARCH_RETRIES);
                if (queries.size() > before) addedAliases++;
            }
            return immutableValues(queries);
        }

        // 3. Multi-artist credit with consistent structured members: substitute official aliases per member.
        if (completeStructured) {
            String[] substituted = new String[members.size()];
            boolean changed = false;
            String leadAlias = "";
            for (int i = 0; i < members.size(); i++) {
                Member member = members.get(i);
                if (!member.aliases.isEmpty()) {
                    substituted[i] = member.aliases.get(0);
                    changed = true;
                    if (i == 0) leadAlias = member.aliases.get(0);
                } else {
                    substituted[i] = QQMatch.cleanSearchArtist(member.name);
                }
            }
            if (changed) {
                StringBuilder joined = new StringBuilder();
                for (String part : substituted) {
                    if (part == null || part.trim().isEmpty()) continue;
                    if (joined.length() > 0) joined.append(' ');
                    joined.append(part.trim());
                }
                if (joined.length() > 0) {
                    addQuery(queries, primaryKey, cleanTitle + " " + joined, MAX_MEMBER_SEARCH_RETRIES);
                }
            }
            if (!leadAlias.isEmpty()) {
                addQuery(queries, primaryKey, cleanTitle + " " + leadAlias, MAX_MEMBER_SEARCH_RETRIES);
            }
            return immutableValues(queries);
        }

        int[] mapped = (members != null && !members.isEmpty()) ? mapConsistentMembers(visibleParts, members) : null;
        if (mapped != null) {
            List<String> rawParts = QQMatch.artistRawParts(rawArtist);
            String[] substituted = new String[visibleParts.size()];
            for (int i = 0; i < visibleParts.size(); i++) {
                substituted[i] = i < rawParts.size() ? QQMatch.cleanSearchArtist(rawParts.get(i)) : "";
            }
            boolean changed = false;
            String leadAlias = "";
            for (int i = 0; i < members.size(); i++) {
                int idx = mapped[i];
                if (idx < 0 || idx >= substituted.length) continue;
                Member member = members.get(i);
                if (member.aliases.isEmpty()) continue;
                String chosen = member.aliases.get(0);
                substituted[idx] = chosen;
                changed = true;
                if (idx == 0) leadAlias = chosen;
            }
            if (changed) {
                StringBuilder joined = new StringBuilder();
                for (String part : substituted) {
                    if (part == null || part.trim().isEmpty()) continue;
                    if (joined.length() > 0) joined.append(' ');
                    joined.append(part.trim());
                }
                if (joined.length() > 0) {
                    addQuery(queries, primaryKey, cleanTitle + " " + joined, MAX_MEMBER_SEARCH_RETRIES);
                }
            }
            if (!leadAlias.isEmpty()) {
                addQuery(queries, primaryKey, cleanTitle + " " + leadAlias, MAX_MEMBER_SEARCH_RETRIES);
            }
        }
        return immutableValues(queries);
    }

    private static String queryKey(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static void addQuery(LinkedHashMap<String, String> queries, String primaryKey, String candidate, int max) {
        if (queries.size() >= max || candidate == null) return;
        String trimmed = candidate.trim().replaceAll("\\s+", " ");
        String key = queryKey(trimmed);
        if (key.isEmpty() || key.equals(primaryKey) || queries.containsKey(key)) return;
        queries.put(key, trimmed);
    }

    static List<Member> membersFromArtists(JSONArray artists) {
        if (artists == null || artists.length() == 0) return Collections.emptyList();
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < artists.length() && members.size() < MAX_MEMBER_COUNT; i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = clean(artist.optString("name", ""));
            if (name.isEmpty()) name = clean(artist.optString("singerName", ""));
            if (name.isEmpty()) continue;
            String providerId = extractProviderId(artist);
            List<String> aliases = extractSingleArtistAliases(artist, name);
            members.add(new Member(name, providerId, aliases));
        }
        return members.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(members);
    }

    static List<Member> membersFromJson(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < array.length() && members.size() < MAX_MEMBER_COUNT; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String name = clean(item.optString("name", ""));
            if (name.isEmpty()) continue;
            String providerId = cleanProviderId(item.optString("providerId", ""));
            List<String> aliases = new ArrayList<>();
            JSONArray rawAliases = item.optJSONArray("aliases");
            for (int j = 0; rawAliases != null && j < rawAliases.length() && aliases.size() < MAX_ALIAS_COUNT; j++) {
                Object entry = rawAliases.opt(j);
                if (entry instanceof String) aliases.add((String) entry);
            }
            members.add(new Member(name, providerId, aliases));
        }
        return members.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(members);
    }

    static JSONArray membersToJson(List<Member> members) {
        JSONArray array = new JSONArray();
        if (members == null) return array;
        for (Member member : members) {
            if (member == null || member.name.isEmpty() || array.length() >= MAX_MEMBER_COUNT) continue;
            try {
                JSONObject item = new JSONObject().put("name", member.name);
                if (!member.providerId.isEmpty()) item.put("providerId", member.providerId);
                JSONArray aliases = toJsonArray(member.aliases);
                if (aliases.length() > 0) item.put("aliases", aliases);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        return array;
    }

    static List<Member> fallbackMembers(String artist, List<String> flatAliases) {
        String cleanArtist = artist == null ? "" : artist.trim();
        if (cleanArtist.isEmpty()) return Collections.emptyList();
        if (isSingleArtistCredit(cleanArtist)) {
            if (QQMatch.artistParts(cleanArtist).isEmpty()) return Collections.emptyList();
            return Collections.singletonList(new Member(cleanArtist, "", flatAliases));
        }
        List<String> rawParts = QQMatch.artistRawParts(cleanArtist);
        List<Member> members = new ArrayList<>();
        for (String segment : rawParts) {
            String trimmed = clean(segment);
            if (trimmed.isEmpty() || QQMatch.artistParts(trimmed).isEmpty()) continue;
            members.add(new Member(trimmed, "", Collections.emptyList()));
            if (members.size() >= MAX_MEMBER_COUNT) break;
        }
        return members.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(members);
    }

    static List<String> fromArtists(JSONArray artists) {
        if (artists == null || artists.length() == 0) return Collections.emptyList();
        Map<String, Boolean> canonicalNames = new LinkedHashMap<>();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            addCanonical(canonicalNames, artist.optString("name", ""));
        }

        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            for (String field : ALIAS_FIELDS) addAliasValues(aliases, canonicalNames, artist.opt(field));
        }
        return immutableValues(aliases);
    }

    static List<String> fromAliasArray(JSONArray values, String canonicalArtist) {
        if (values == null || values.length() == 0) return Collections.emptyList();
        Map<String, Boolean> canonicalNames = new LinkedHashMap<>();
        addCanonical(canonicalNames, canonicalArtist);
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        addAliasValues(aliases, canonicalNames, values);
        return immutableValues(aliases);
    }

    static List<String> fromExactNeteaseSong(JSONObject response, String songId) {
        if (response == null || songId == null || songId.trim().isEmpty()) return Collections.emptyList();
        JSONObject result = response.optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            Object foundId = song.opt("id");
            if (foundId != null && songId.equals(String.valueOf(foundId))) {
                return fromArtists(song.optJSONArray("ar"));
            }
        }
        return Collections.emptyList();
    }

    static JSONArray toJsonArray(List<String> aliases) {
        JSONArray values = new JSONArray();
        if (aliases == null) return values;
        for (String alias : aliases) {
            String clean = clean(alias);
            if (!clean.isEmpty() && values.length() < MAX_ALIAS_COUNT) values.put(clean);
        }
        return values;
    }

    private static String extractProviderId(JSONObject artist) {
        String providerId = cleanProviderId(artist.optString("providerId", ""));
        if (!providerId.isEmpty()) return providerId;
        String mid = cleanProviderId(artist.optString("mid", ""));
        if (!mid.isEmpty()) return mid;
        Object rawId = artist.opt("id");
        if (rawId instanceof Number) {
            long id = ((Number) rawId).longValue();
            return id > 0 ? Long.toString(id) : "";
        }
        return rawId instanceof String ? cleanProviderId((String) rawId) : "";
    }

    private static List<String> extractSingleArtistAliases(JSONObject artist, String canonicalName) {
        if (containsExplicitJoinSeparator(canonicalName) || QQMatch.memberAtomNames(canonicalName).isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Boolean> canonicalNames = new LinkedHashMap<>();
        addCanonicalAtom(canonicalNames, canonicalName);
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (String field : ALIAS_FIELDS) addAliasValues(aliases, canonicalNames, artist.opt(field));
        return immutableValues(aliases);
    }

    private static List<String> cleanMemberAliases(String memberName, List<String> rawAliases) {
        if (rawAliases == null || rawAliases.isEmpty()
                || containsExplicitJoinSeparator(memberName) || QQMatch.memberAtomNames(memberName).isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Boolean> canonicalNames = new LinkedHashMap<>();
        addCanonicalAtom(canonicalNames, memberName);
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (String value : rawAliases) addAlias(aliases, canonicalNames, value);
        return immutableValues(aliases);
    }

    private static void addCanonical(Map<String, Boolean> names, String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return;
        for (String token : QQMatch.artistNames(clean)) names.put(token, Boolean.TRUE);
    }

    private static void addCanonicalAtom(Map<String, Boolean> names, String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return;
        for (String token : QQMatch.memberAtomNames(clean)) names.put(token, Boolean.TRUE);
    }

    private static void addAliasValues(LinkedHashMap<String, String> aliases,
                                       Map<String, Boolean> canonicalNames, Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && aliases.size() < MAX_ALIAS_COUNT; i++) {
                Object item = array.opt(i);
                if (item instanceof String) addAlias(aliases, canonicalNames, (String) item);
            }
        } else if (value instanceof String) {
            addAlias(aliases, canonicalNames, (String) value);
        }
    }

    private static void addAlias(LinkedHashMap<String, String> aliases,
                                 Map<String, Boolean> canonicalNames, String value) {
        if (aliases.size() >= MAX_ALIAS_COUNT) return;
        String clean = clean(value);
        if (clean.isEmpty() || !isSingleArtistCredit(clean)) return;
        String normalized = QQMatch.normalizeArtistToken(clean);
        if (normalized.isEmpty() || canonicalNames.containsKey(normalized) || aliases.containsKey(normalized)) return;
        aliases.put(normalized, clean);
    }

    private static List<String> immutableValues(LinkedHashMap<String, String> values) {
        if (values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values.values()));
    }

    private static String clean(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.isEmpty() || "null".equalsIgnoreCase(normalized)) return "";
        if (normalized.length() > MAX_ALIAS_LENGTH) return "";
        return normalized;
    }

    private static String cleanProviderId(String value) {
        String clean = clean(value);
        if (clean.isEmpty() || "0".equals(clean)) return "";
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c <= 0x20 || c == 0x7f) return "";
        }
        return clean;
    }

    private static boolean isSingleArtistCredit(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '/' || c == '\\' || c == ',' || c == '\u3001' || c == '\uFF0C'
                    || c == '&' || c == '\uFF06' || c == ';' || c == '\n' || c == '\r') return false;
        }
        return !COLLABORATION.matcher(clean.toLowerCase(Locale.ROOT)).find();
    }
}
