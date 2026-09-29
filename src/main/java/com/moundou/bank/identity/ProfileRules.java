package com.moundou.bank.identity;

import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules for a member's own profile (Auth.Roles-6, Auth.Roles-11, CON-8), as pure
 * code for the fast suite.
 */
public final class ProfileRules {

    public static final String DISPLAY_NAME = "displayName";
    public static final String TIME_ZONE = "timeZone";

    static final String NAME_REQUIRED = "profile.displayName.required";
    static final String NAME_TOO_LONG = "profile.displayName.tooLong";
    static final String ZONE_UNKNOWN = "profile.timeZone.unknown";

    static final int MAX_NAME_LENGTH = 80;

    /**
     * The zones a member may choose: IANA region names such as Africa/Douala. Java also
     * knows fixed offsets and legacy aliases ("GMT+1", "SystemV/EST5"), which do not follow
     * daylight saving the way the place does, so they are left out.
     */
    public static final List<String> ZONES = ZoneId.getAvailableZoneIds().stream()
            .filter(id -> id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV/"))
            .sorted()
            .toList();

    private static final Set<String> ZONE_SET = Set.copyOf(ZONES);

    private ProfileRules() { }

    public record Profile(String displayName, ZoneId timeZone) { }

    public sealed interface Result {
        record Valid(Profile profile) implements Result { }
        record Invalid(Map<String, String> fieldErrors) implements Result { }
    }

    public static Result validate(String displayName, String timeZone) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty()) {
            errors.put(DISPLAY_NAME, NAME_REQUIRED);
        } else if (name.length() > MAX_NAME_LENGTH) {
            errors.put(DISPLAY_NAME, NAME_TOO_LONG);
        }
        if (timeZone == null || !ZONE_SET.contains(timeZone)) {
            errors.put(TIME_ZONE, ZONE_UNKNOWN);
        }
        return errors.isEmpty()
                ? new Result.Valid(new Profile(name, ZoneId.of(timeZone)))
                : new Result.Invalid(Collections.unmodifiableMap(errors));
    }
}
