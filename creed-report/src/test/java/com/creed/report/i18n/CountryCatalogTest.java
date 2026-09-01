package com.creed.report.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins how the two presentation axes combine — the part a wrong answer silently mistranslates. */
class CountryCatalogTest {

    private final CountryCatalog catalog = new CountryCatalog(new CountryProperties());

    @Test
    void countryContributesTheRegionSubtagSoRegionBundlesResolve() {
        assertThat(catalog.profile(ReportCountry.MY, Locale.ENGLISH).locale())
                .isEqualTo(Locale.of("en", "MY"));
        assertThat(catalog.profile(ReportCountry.TH, Locale.forLanguageTag("th")).locale())
                .isEqualTo(Locale.of("th", "TH"));
    }

    @Test
    void globalHasNoRegionSoScriptVariantsSurvive() {
        // The reason GLOBAL's region is empty rather than a placeholder: overwriting the region
        // would collapse zh-CN and zh-TW onto bare zh, i.e. Traditional would silently go away.
        assertThat(catalog.profile(ReportCountry.GLOBAL, Locale.TRADITIONAL_CHINESE).locale())
                .isEqualTo(Locale.of("zh", "TW"));
        assertThat(catalog.profile(ReportCountry.GLOBAL, Locale.SIMPLIFIED_CHINESE).locale())
                .isEqualTo(Locale.of("zh", "CN"));
    }

    @Test
    void aLanguageTheCountryDoesNotOfferFallsBackToItsDefault() {
        // A country renders in its own languages: ?country=th&lang=fr is Thai, not French chrome.
        assertThat(catalog.profile(ReportCountry.TH, Locale.FRENCH).locale())
                .isEqualTo(Locale.of("th", "TH"));
        assertThat(catalog.profile(ReportCountry.VN, Locale.SIMPLIFIED_CHINESE).locale())
                .isEqualTo(Locale.of("vi", "VN"));
    }

    @Test
    void aLanguageTheCountryDoesOfferIsKept() {
        assertThat(catalog.profile(ReportCountry.MY, Locale.forLanguageTag("ms")).locale())
                .isEqualTo(Locale.of("ms", "MY"));
        assertThat(catalog.profile(ReportCountry.TH, Locale.ENGLISH).locale())
                .isEqualTo(Locale.of("en", "TH"));
    }

    @Test
    void resolutionPrefersTheParameterThenTheCookieThenTheLanguage() {
        assertThat(catalog.resolve("vn", "my", Locale.forLanguageTag("th-TH"))).isEqualTo(ReportCountry.VN);
        assertThat(catalog.resolve(null, "my", Locale.forLanguageTag("th-TH"))).isEqualTo(ReportCountry.MY);
        // Accept-Language: th-TH — the region subtag names the country.
        assertThat(catalog.resolve(null, null, Locale.forLanguageTag("th-TH"))).isEqualTo(ReportCountry.TH);
        // Accept-Language: vi — no region, but it is exactly one country's default language.
        assertThat(catalog.resolve(null, null, Locale.forLanguageTag("vi"))).isEqualTo(ReportCountry.VN);
    }

    @Test
    void unusableValuesFallThroughInsteadOfFailing() {
        // Country is a presentation preference; a bad value must not 400 the page.
        assertThat(catalog.resolve("atlantis", null, Locale.ENGLISH)).isEqualTo(ReportCountry.GLOBAL);
        assertThat(catalog.resolve(null, null, Locale.forLanguageTag("de-AT"))).isEqualTo(ReportCountry.GLOBAL);
        assertThat(catalog.resolve(null, null, null)).isEqualTo(ReportCountry.GLOBAL);
    }

    @Test
    void theEffectiveLocaleNamesTheCountryBackAgain() {
        // profileFor is the inverse used by the controllers and exporters, so it must round-trip.
        for (ReportCountry country : ReportCountry.values()) {
            Locale effective = catalog.profile(country, Locale.ENGLISH).locale();
            assertThat(catalog.profileFor(effective).country())
                    .as("round trip for %s", country.code()).isEqualTo(country);
        }
    }

    @Test
    void configurationOverridesOneFieldAndKeepsTheRest() {
        CountryProperties properties = new CountryProperties();
        CountryProperties.Profile override = new CountryProperties.Profile();
        override.setDatePattern("dd-MM-yyyy");
        properties.getProfiles().put(ReportCountry.MY, override);

        CountryProfile profile = new CountryCatalog(properties).profile(ReportCountry.MY, Locale.ENGLISH);
        assertThat(profile.datePattern()).isEqualTo("dd-MM-yyyy");
        assertThat(profile.languages()).isEqualTo(List.of("ms", "en"));
        assertThat(profile.calendar()).isEqualTo(ReportCountry.Calendar.ISO);
    }

    @Test
    void anUnusableOverrideFailsAtStartupNamingTheCountry() {
        CountryProperties properties = new CountryProperties();
        CountryProperties.Profile override = new CountryProperties.Profile();
        override.setDatePattern("dd/QQQQQQ");
        properties.getProfiles().put(ReportCountry.VN, override);

        assertThatThrownBy(() -> new CountryCatalog(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vn");
    }

    @Test
    void codeAndRegionBothIdentifyACountry() {
        assertThat(ReportCountry.of("TH")).contains(ReportCountry.TH);
        assertThat(ReportCountry.of(" vn ")).contains(ReportCountry.VN);
        assertThat(ReportCountry.of("xx")).isEmpty();
        assertThat(ReportCountry.byRegion("MY")).contains(ReportCountry.MY);
        // GLOBAL has no region, so nothing may resolve to it by region.
        assertThat(ReportCountry.byRegion("")).isEmpty();
    }
}
