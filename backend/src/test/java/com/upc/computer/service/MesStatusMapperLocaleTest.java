package com.upc.computer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ResourceLock("java.util.Locale.default")
class MesStatusMapperLocaleTest {
    @Test
    void roleRoutingIsIndependentOfTurkishSystemLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("admin", MesStatusMapper.toRoleKey("admin"));
            assertEquals("device", MesStatusMapper.toRoleKey("device"));
            assertEquals("aftersale", MesStatusMapper.toRoleKey("service"));
            assertEquals("visitor", MesStatusMapper.toRoleKey("VISITOR"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void handlesKnownUnknownAndMissingRoles() {
        assertEquals("system", MesStatusMapper.toRoleKey(null));
        assertEquals("quality", MesStatusMapper.toRoleKey("qc"));
        assertEquals("custom", MesStatusMapper.toRoleKey("CUSTOM"));
        assertEquals("", MesStatusMapper.toRoleKey(""));
    }
}
