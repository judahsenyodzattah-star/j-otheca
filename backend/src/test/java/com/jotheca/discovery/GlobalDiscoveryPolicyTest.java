package com.jotheca.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jotheca.discovery.policy.DiscoverabilityPolicyEvaluator;
import com.jotheca.discovery.policy.EditionKeyGenerator;
import com.jotheca.discovery.policy.IsbnNormalizer;
import com.jotheca.tenancy.model.CatalogVisibility;
import com.jotheca.tenancy.model.LibraryStatus;
import org.junit.jupiter.api.Test;

class GlobalDiscoveryPolicyTest {

    @Test
    void publicCatalogRequiresExplicitBookOptInDuringFoundationRollout() {
        assertThat(eligible(CatalogVisibility.PUBLIC, true)).isTrue();
        assertThat(eligible(CatalogVisibility.PUBLIC, false)).isFalse();
    }

    @Test
    void partiallyPublicCatalogAlsoRequiresExplicitBookOptIn() {
        assertThat(eligible(CatalogVisibility.PARTIALLY_PUBLIC, true)).isTrue();
        assertThat(eligible(CatalogVisibility.PARTIALLY_PUBLIC, false)).isFalse();
    }

    @Test
    void membersOnlyAndInactiveOrHiddenRecordsAreAlwaysExcluded() {
        assertThat(eligible(CatalogVisibility.MEMBERS_ONLY, true)).isFalse();
        assertThat(DiscoverabilityPolicyEvaluator.isDiscoverable(
                LibraryStatus.SUSPENDED, true, CatalogVisibility.PUBLIC, true, true)).isFalse();
        assertThat(DiscoverabilityPolicyEvaluator.isDiscoverable(
                LibraryStatus.ACTIVE, false, CatalogVisibility.PUBLIC, true, true)).isFalse();
        assertThat(DiscoverabilityPolicyEvaluator.isDiscoverable(
                LibraryStatus.ACTIVE, true, CatalogVisibility.PUBLIC, false, true)).isFalse();
    }

    @Test
    void normalizesValidIsbn10AndIsbn13ToTheSameCanonicalKey() {
        assertThat(IsbnNormalizer.normalize("0-306-40615-2")).isEqualTo("9780306406157");
        assertThat(IsbnNormalizer.normalize("978-0-306-40615-7")).isEqualTo("9780306406157");
        assertThat(EditionKeyGenerator.forRecord("book-a", "0-306-40615-2"))
                .isEqualTo(EditionKeyGenerator.forRecord("book-b", "9780306406157"));
    }

    @Test
    void rejectsMalformedAndBadCheckDigitIsbns() {
        assertThatThrownBy(() -> IsbnNormalizer.normalize("123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsbnNormalizer.normalize("9780132350885"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsbnNormalizer.normalize("0306406153"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingOrAmbiguousRecordsRemainSourceSpecific() {
        assertThat(IsbnNormalizer.normalize(null)).isNull();
        assertThat(EditionKeyGenerator.forRecord("book-a", null).isbnBacked()).isFalse();
        assertThat(EditionKeyGenerator.forRecord("book-a", null).value())
                .isNotEqualTo(EditionKeyGenerator.forRecord("book-b", null).value());
        assertThat(EditionKeyGenerator.forRecord("book-a", " ").value())
                .isEqualTo("source:book-a");
    }

    private static boolean eligible(CatalogVisibility visibility, boolean optIn) {
        return DiscoverabilityPolicyEvaluator.isDiscoverable(
                LibraryStatus.ACTIVE, true, visibility, true, optIn);
    }
}
