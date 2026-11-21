package com.moundou.bank.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast suite. Model 6 DT-1, the decision about degraded mode, with no database: the
 * reader is replaced by a double that either answers or fails the way an unreachable
 * database fails, with a DataAccessException (Technical Design 14.4).
 *
 * The real failure, a paused Postgres container, is DashboardDegradedIT's (ADR-009,
 * CI only). This class pins the rules so they can be written and checked first.
 *
 * <h2>The API these tests fix</h2>
 * <pre>
 *   new DashboardService(DashboardReader reader, DashboardCache cache)
 *   HomeView   home(UUID member)                         Live(Summary) | Offline(Optional&lt;Summary&gt;)
 *   PeopleView people(UUID member)                       Live(List&lt;PersonBalance&gt;) | Offline()
 *   PersonView person(UUID member, UUID person)          Live(PersonDetail) | Offline()
 *
 *   DashboardCache: void put(UUID member, Summary summary); Optional&lt;Summary&gt; get(UUID member)
 * </pre>
 * DashboardReader needs a public constructor taking (TransactionRepository,
 * MemberDirectory) and methods that are not final, so this test can override them, and
 * so can Spring's proxy for @Transactional.
 */
class DashboardServiceTests {

    private static final UUID ME = UUID.randomUUID();
    private static final Currency USD = Currency.getInstance("USD");

    private final Summary yesterday = new Summary(List.of(new CurrencyPosition(USD, 2_000, List.of())));
    private final Summary today = new Summary(List.of(new CurrencyPosition(USD, 3_500, List.of())));

    private final DashboardCache cache = new DashboardCache();

    @Test
    void aLiveLoadIsShownAndReplacesWhatWasCached() {                            // DT-1 R1
        cache.put(ME, yesterday);
        DashboardService service = new DashboardService(answering(today), cache);

        assertThat(service.home(ME)).isEqualTo(new HomeView.Live(today));
        assertThat(cache.get(ME)).contains(today);
    }

    @Test
    void anEmptySummaryIsCachedToo() {                                           // DT-1 R2
        cache.put(ME, yesterday);
        DashboardService service = new DashboardService(answering(new Summary(List.of())), cache);

        assertThat(service.home(ME)).isEqualTo(new HomeView.Live(new Summary(List.of())));
        assertThat(cache.get(ME)).contains(new Summary(List.of()));
    }

    @Test
    void whenTheDatabaseFailsTheLastSummaryIsShownOffline() {                    // DT-1 R3, T-Net-5a
        cache.put(ME, yesterday);
        DashboardService service = new DashboardService(failing(), cache);

        assertThat(service.home(ME)).isEqualTo(new HomeView.Offline(Optional.of(yesterday)));
        assertThat(cache.get(ME)).as("a failure leaves the cache alone").contains(yesterday);
    }

    @Test
    void whenTheDatabaseFailsWithNothingCachedThereAreNoFigures() {             // DT-1 R4
        DashboardService service = new DashboardService(failing(), cache);

        assertThat(service.home(ME)).isEqualTo(new HomeView.Offline(Optional.empty()));
    }

    @Test
    void theCacheIsPerMember() {                                                 // DT-1 R4, SEC-2
        cache.put(UUID.randomUUID(), yesterday);
        DashboardService service = new DashboardService(failing(), cache);

        assertThat(service.home(ME)).isEqualTo(new HomeView.Offline(Optional.empty()));
    }

    @Test
    void peopleAndOnePersonHaveNothingCached() {                                 // Model 6 Q7
        cache.put(ME, yesterday);
        DashboardService service = new DashboardService(failing(), cache);

        assertThat(service.people(ME)).isEqualTo(new PeopleView.Offline());
        assertThat(service.person(ME, UUID.randomUUID())).isEqualTo(new PersonView.Offline());
    }

    @Test
    void onlyADatabaseFailureMeansOffline() {                                    // Technical Design 14.4
        DashboardService service = new DashboardService(throwing(new IllegalStateException("a bug")), cache);

        assertThatThrownBy(() -> service.home(ME)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.people(ME)).isInstanceOf(IllegalStateException.class);
    }

    // ---- reader doubles -------------------------------------------------------------

    private static DashboardReader answering(Summary summary) {
        return new DashboardReader(null, null) {
            @Override public Summary summary(UUID member) { return summary; }
        };
    }

    private static DashboardReader failing() {
        return throwing(new DataAccessResourceFailureException("database unreachable"));
    }

    private static DashboardReader throwing(RuntimeException failure) {
        return new DashboardReader(null, null) {
            @Override public Summary summary(UUID member) { throw failure; }
            @Override public List<PersonBalance> balances(UUID member) { throw failure; }
            @Override public Optional<PersonDetail> withPerson(UUID member, UUID person) { throw failure; }
        };
    }
}