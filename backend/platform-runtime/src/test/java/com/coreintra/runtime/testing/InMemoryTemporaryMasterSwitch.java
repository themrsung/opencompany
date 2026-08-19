package com.coreintra.runtime.testing;

import com.coreintra.runtime.support.TemporaryMasterSwitchRepository;
import com.coreintra.runtime.support.TemporaryMasterSwitchRow;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Optional;

/**
 * The one-row kill switch, seeded as the migration seeds it.
 *
 * <p>Built by reflection because the row has no public constructor: it is
 * created once by {@code V9__platform_runtime.sql} and thereafter only ever
 * updated. A test-only factory method on the class itself would be a second way
 * to make one, which is exactly what the design is avoiding.
 */
public final class InMemoryTemporaryMasterSwitch implements TemporaryMasterSwitchRepository {

    private TemporaryMasterSwitchRow row = seeded();

    @Override
    public TemporaryMasterSwitchRow save(TemporaryMasterSwitchRow saved) {
        this.row = saved;
        return saved;
    }

    @Override
    public Optional<TemporaryMasterSwitchRow> findById(String installation) {
        return Optional.of(row);
    }

    private static TemporaryMasterSwitchRow seeded() {
        try {
            Constructor<TemporaryMasterSwitchRow> constructor =
                    TemporaryMasterSwitchRow.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            TemporaryMasterSwitchRow created = constructor.newInstance();
            Field installation = TemporaryMasterSwitchRow.class.getDeclaredField("installation");
            installation.setAccessible(true);
            installation.set(created, TemporaryMasterSwitchRow.INSTALLATION);
            return created;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build the seeded switch row", e);
        }
    }
}
