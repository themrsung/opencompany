package com.coreintra.app.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.coreintra.compat.Immutables;
import com.coreintra.runtime.support.TemporaryMasterCapabilities;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wording catalogue is a safety control, not copy.
 *
 * <p>§8 requires the issuance screen and the live banner to state what a ticked
 * capability allows in end-user terms. If the catalogue and the never-grantable
 * list drift apart, one of two bad things happens: a capability becomes
 * un-issuable because nobody wrote its sentence, or — much worse — a capability
 * that must never be grantable appears in the tick list looking ordinary.
 */
class SupportCapabilityWordingTest {

    @Test
    @DisplayName("nothing on the never-grantable list is offered in the tick list")
    void neverGrantableIsNotOffered() {
        List<String> offered = SupportCapabilityWording.keys();

        List<String> forbiddenButOffered = new ArrayList<String>();
        for (String forbidden : TemporaryMasterCapabilities.NEVER_GRANTABLE) {
            if (offered.contains(forbidden)) {
                forbiddenButOffered.add(forbidden);
            }
        }

        assertThat(forbiddenButOffered)
                .as("a capability that lets a support session escape its own boundaries must not "
                        + "appear on the issuance screen at all, however it is worded")
                .isEmpty();
    }

    @Test
    @DisplayName("every offered capability has a sentence in both languages")
    void everythingOfferedIsDescribed() {
        for (String key : SupportCapabilityWording.keys()) {
            String description = SupportCapabilityWording.describe(key);
            assertThat(description).as(key).isNotNull();
            // "한국어 (English)" — the brackets are the English half.
            assertThat(description).as(key).contains("(").endsWith(")");
        }
    }

    @Test
    @DisplayName("a description never leaks the permission string it stands for")
    void descriptionsAreInEndUserTerms() {
        for (String key : SupportCapabilityWording.keys()) {
            assertThat(SupportCapabilityWording.describe(key))
                    .as("§8 asks for \"read every employee's salary history\", not \"" + key + "\"")
                    .doesNotContain(key);
        }
    }

    @Test
    @DisplayName("an undescribed capability blocks issuance rather than passing through as its id")
    void undescribedCapabilityIsRefused() {
        assertThat(SupportCapabilityWording.fullyDescribed(
                Immutables.listOf("hr.employee:read"))).isTrue();
        assertThat(SupportCapabilityWording.fullyDescribed(
                Immutables.listOf("hr.employee:read", "something.new:read"))).isFalse();
    }

    @Test
    @DisplayName("the salary capability says what it means, because that is the one the brief names")
    void salaryReadsAsTheBriefWroteIt() {
        assertThat(SupportCapabilityWording.describe("hr.compensation:read"))
                .contains("급여 이력")
                .contains("salary history");
    }
}
